(ns matrix
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.walk :as w]
            [clojure.spec.alpha :as s]
            [crux.io :as cio]
            [crux.kv :as kv]
            [crux.memory :as mem]
            [crux.rdf :as rdf]
            [crux.query :as q]
            [taoensso.nippy :as nippy])
  (:import [java.util Arrays ArrayList Date HashMap HashSet IdentityHashMap Map]
           [java.util.function Function IntFunction]
           [clojure.lang ILookup Indexed]
           crux.ByteUtils
           [java.io DataInputStream DataOutput DataOutputStream]
           java.nio.ByteOrder
           [org.agrona.collections Hashing Int2ObjectHashMap Object2IntHashMap]
           org.agrona.concurrent.UnsafeBuffer
           org.agrona.ExpandableDirectByteBuffer
           org.agrona.DirectBuffer
           [org.agrona.io DirectBufferInputStream ExpandableDirectBufferOutputStream]
           [org.roaringbitmap BitmapDataProviderSupplier FastRankRoaringBitmap ImmutableBitmapDataProvider IntConsumer RoaringBitmap]
           [org.roaringbitmap.buffer ImmutableRoaringBitmap MutableRoaringBitmap]
           [org.roaringbitmap.longlong LongConsumer Roaring64NavigableMap]
           [org.ejml.data DMatrix DMatrixRMaj
            DMatrixSparse DMatrixSparseCSC]
           org.ejml.dense.row.CommonOps_DDRM
           [org.ejml.sparse.csc CommonOps_DSCC MatrixFeatures_DSCC]
           org.ejml.generic.GenericMatrixOps_F64))

(set! *unchecked-math* :warn-on-boxed)

;; Matrix / GraphBLAS style breath first search
;; https://redislabs.com/redis-enterprise/technology/redisgraph/
;; MAGiQ http://www.vldb.org/pvldb/vol11/p1978-jamour.pdf
;; gSMat https://arxiv.org/pdf/1807.07691.pdf

(defn square-matrix ^DMatrixSparseCSC [size]
  (DMatrixSparseCSC. size size))

(defn row-vector ^DMatrixSparseCSC [size]
  (DMatrixSparseCSC. 1 size))

(defn col-vector ^DMatrixSparseCSC [size]
  (DMatrixSparseCSC. size 1))

(defn equals-matrix [^DMatrix a ^DMatrix b]
  (GenericMatrixOps_F64/isEquivalent a b 0.0))

(defn resize-matrix [^DMatrixSparse m new-size]
  (let [grown (square-matrix new-size)]
    (CommonOps_DSCC/extract m
                            0
                            (.getNumCols m)
                            0
                            (.getNumRows m)
                            grown
                            0
                            0)
    grown))

(defn ensure-matrix-capacity ^DMatrixSparseCSC [^DMatrixSparseCSC m size factor]
  (if (> (long size) (.getNumRows m))
    (resize-matrix m (* (long factor) (long size)))
    m))

(defn round-to-power-of-two [^long x ^long stride]
  (bit-and (bit-not (dec stride))
           (dec (+ stride x))))

(defn load-rdf-into-matrix-graph [resource]
  (with-open [in (io/input-stream (io/resource resource))]
    (let [{:keys [value->id
                  eid->matrix]}
          (->> (rdf/ntriples-seq in)
               (map rdf/rdf->clj)
               (reduce (fn [{:keys [value->id eid->matrix]} [s p o]]
                         (let [value->id (reduce (fn [value->id v]
                                                   (update value->id v (fn [x]
                                                                         (or x (count value->id)))))
                                                 value->id
                                                 [s p o])]
                           {:eid->matrix (update eid->matrix
                                                 p
                                                 (fn [x]
                                                   (let [s-id (long (get value->id s))
                                                         o-id (long (get value->id o))
                                                         size (count value->id)
                                                         m (if x
                                                             (ensure-matrix-capacity x size 2)
                                                             (square-matrix size))]
                                                     (doto m
                                                       (.unsafe_set s-id o-id 1.0)))))
                            :value->id value->id}))))
          max-size (round-to-power-of-two (count value->id) 64)]
      {:eid->matrix (->> (for [[k ^DMatrixSparseCSC v] eid->matrix]
                           [k (ensure-matrix-capacity v max-size 1)])
                         (into {}))
       :value->id value->id
       :id->value (->> (set/map-invert value->id)
                       (into (sorted-map)))
       :max-size max-size})))

(defn print-assigned-values [{:keys [id->value] :as graph} ^DMatrix m]
  (if (instance? DMatrixSparse m)
    (.printNonZero ^DMatrixSparse m)
    (.print m))
  (doseq [r (range (.getNumRows m))
          c (range (.getNumCols m))
          :when (= 1.0 (.unsafe_get m r c))]
    (if (= 1 (.getNumRows m))
      (prn (get id->value c))
      (prn (get id->value r) (get id->value c))))
  (prn))

;; TODO: Couldn't this be a vector in most cases?
(defn new-constant-matix ^DMatrixSparseCSC [{:keys [value->id max-size] :as graph} & vs]
  (let [m (square-matrix max-size)]
    (doseq [v vs
            :let [id (get value->id v)]
            :when id]
      (.unsafe_set m id id 1.0))
    m))

(defn transpose-matrix ^DMatrixSparseCSC [^DMatrixSparseCSC m]
  (CommonOps_DSCC/transpose m nil nil))

(defn boolean-matrix ^DMatrixSparseCSC [^DMatrixSparseCSC m]
  (dotimes [n (alength (.nz_values m))]
    (when (pos? (aget (.nz_values m) n))
      (aset (.nz_values m) n 1.0)))
  m)

(defn ^DMatrixSparseCSC assign-mask
  ([^DMatrixSparseCSC mask ^DMatrixSparseCSC w u]
   (assign-mask mask w u pos?))
  ([^DMatrixSparseCSC mask ^DMatrixSparseCSC w u pred]
   (assert (= 1 (.getNumCols mask) (.getNumCols w)))
   (dotimes [n (min (.getNumRows mask) (.getNumRows w))]
     (when (pred (.get mask n 0))
       (.set w n 0 (double u))))
   w))

(defn mask ^DMatrixSparseCSC [^DMatrixSparseCSC mask ^DMatrixSparseCSC w]
  (assign-mask mask w 0.0 zero?))

(defn inverse-mask ^DMatrixSparseCSC [^DMatrixSparseCSC mask ^DMatrixSparseCSC w]
  (assign-mask mask w 0.0 pos?))

(defn multiply-matrix ^DMatrixSparseCSC [^DMatrixSparseCSC a ^DMatrixSparseCSC b]
  (doto (DMatrixSparseCSC. (.getNumRows a) (.getNumCols b))
    (->> (CommonOps_DSCC/mult a b))))

(defn or-matrix ^DMatrixSparseCSC [^DMatrixSparseCSC a ^DMatrixSparseCSC b]
  (->> (multiply-matrix a b)
       (boolean-matrix)))

(defn multiply-elements-matrix ^DMatrixSparseCSC [^DMatrixSparseCSC a ^DMatrixSparseCSC b]
  (let [c (DMatrixSparseCSC. (max (.getNumRows a) (.getNumRows b))
                             (max (.getNumCols a) (.getNumCols b)))]
    (CommonOps_DSCC/elementMult a b c nil nil)
    c))

(defn add-elements-matrix ^DMatrixSparseCSC [^DMatrixSparseCSC a ^DMatrixSparseCSC b]
  (let [c (DMatrixSparseCSC. (max (.getNumRows a) (.getNumRows b))
                             (max (.getNumCols a) (.getNumCols b)))]
    (CommonOps_DSCC/add 1.0 a 1.0 b c nil nil)
    c))

(defn or-elements-matrix ^DMatrixSparseCSC [^DMatrixSparseCSC a ^DMatrixSparseCSC b]
  (->> (add-elements-matrix a b)
       (boolean-matrix)))

;; NOTE: these return row vectors, which they potentially shouldn't?
;; Though the result of this is always fed straight into a diagonal.
(defn matlab-any-matrix ^DMatrixRMaj [^DMatrixSparseCSC m]
  (CommonOps_DDRM/transpose (CommonOps_DSCC/maxCols m nil) nil))

(defn matlab-any-matrix-sparse ^DMatrixSparseCSC [^DMatrixSparseCSC m]
  (let [v (col-vector (.getNumRows m))]
    (doseq [^long x (->> (.col_idx m)
                         (map-indexed vector)
                         (remove (comp zero? second))
                         (partition-by second)
                         (map ffirst))]
      (.unsafe_set v (dec x) 0 1.0))
    v))

(defn diagonal-matrix ^DMatrixSparseCSC [^DMatrix v]
  (let [target-size (.getNumRows v)
        m (square-matrix target-size)]
    (doseq [i (range target-size)
            :let [x (.unsafe_get v i 0)]
            :when (not (zero? x))]
      (.unsafe_set m i i x))
    m))

;; GraphBLAS Tutorial https://github.com/tgmattso/GraphBLAS

(defn graph-blas-tutorial []
  (let [num-nodes 7
        graph (doto (square-matrix num-nodes)
                (.set 0 1 1.0)
                (.set 0 3 1.0)
                (.set 1 4 1.0)
                (.set 1 6 1.0)
                (.set 2 5 1.0)
                (.set 3 0 1.0)
                (.set 3 2 1.0)
                (.set 4 5 1.0)
                (.set 5 2 1.0)
                (.set 6 2 1.0)
                (.set 6 3 1.0)
                (.set 6 4 1.0))
        vec (doto (col-vector num-nodes)
              (.set 2 0 1.0))]
    (println "Exercise 3: Adjacency matrix")
    (println "Matrix: Graoh =")
    (.print graph)

    (println "Exercise 4: Matrix Vector Multiplication")
    (println "Vector: Target node =")
    (.print vec)
    (println "Vector: sources =")
    (.print (multiply-matrix graph vec))

    (println "Exercise 5: Matrix Vector Multiplication")
    (let [vec (doto (col-vector num-nodes)
                (.set 6 0 1.0))]
      (println "Vector: source node =")
      (.print vec)
      (println "Vector: neighbours =")
      (.print (multiply-matrix (transpose-matrix graph) vec)))

    (println "Exercise 7: Traverse the graph")
    (let [w (doto (col-vector num-nodes)
              (.set 0 0 1.0))]
      (println "Vector: wavefront(src) =")
      (.print w)
      (loop [w w
             n 0]
        (when (< n num-nodes)
          ;; TODO: This should really use or and not accumulate.
          (let [w (or-matrix (transpose-matrix graph) w)]
            (println "Vector: wavefront =")
            (.print w)
            (recur w (inc n))))))

    (println "Exercise 9: Avoid revisiting")
    (let [w (doto (col-vector num-nodes)
              (.set 0 0 1.0))
          v (col-vector num-nodes)]
      (println "Vector: wavefront(src) =")
      (.print w)
      (loop [v v
             w w
             n 0]
        (when (< n num-nodes)
          (let [v (or-elements-matrix v w)]
            (println "Vector: visited =")
            (.print v)
            (let [w (inverse-mask v (or-matrix (transpose-matrix graph) w))]
              (println "Vector: wavefront =")
              (.print w)
              (when-not (MatrixFeatures_DSCC/isZeros w 0)
                (recur v w (inc n))))))))

    (println "Exercise 10: level BFS")
    (let [w (doto (col-vector num-nodes)
              (.set 0 0 1.0))
          levels (col-vector num-nodes)]
      (println "Vector: wavefront(src) =")
      (.print w)
      (loop [levels levels
             w w
             lvl 1]
        (let [levels (assign-mask w levels lvl)]
          (println "Vector: levels =")
          (.print levels)
          (let [w (inverse-mask levels (or-matrix (transpose-matrix graph) w))]
            (println "Vector: wavefront =")
            (.print w)
            (when-not (MatrixFeatures_DSCC/isZeros w 0)
              (recur levels w (inc lvl)))))))))

(def ^:const example-data-artists-resource "crux/example-data-artists.nt")

(defn example-data-artists-with-matrix [{:keys [eid->matrix id->value value->id max-size] :as graph}]
  (println "== Data")
  (doseq [[k ^DMatrixSparseCSC v] eid->matrix]
    (prn k)
    (print-assigned-values graph v))

  (println "== Query")
  (let [ ;; ?x :rdf/label "The Potato Eaters" -- backwards, so
        ;; transposing adjacency matrix.
        potato-eaters-label (multiply-matrix
                             (new-constant-matix graph "The Potato Eaters" "Guernica")
                             (transpose-matrix (:http://www.w3.org/2000/01/rdf-schema#label eid->matrix)))
        ;; Create mask for subjects. A mask is a diagonal, like the
        ;; constant matrix. Done to "lift" the new left hand side into
        ;; "focus".
        potato-eaters-mask (->> potato-eaters-label ;; TODO: Why transpose here in MAGiQ paper?
                                (matlab-any-matrix)
                                (diagonal-matrix))
        ;; ?y :example/creatorOf ?x -- backwards, so transposing adjacency
        ;; matrix.
        creator-of (multiply-matrix
                    potato-eaters-mask
                    (transpose-matrix (:http://example.org/creatorOf eid->matrix)))
        ;; ?y :foaf/firstName ?z -- forwards, so no transpose of adjacency matrix.
        creator-of-fname (multiply-matrix
                          (->> creator-of
                               (matlab-any-matrix)
                               (diagonal-matrix))
                          (:http://xmlns.com/foaf/0.1/firstName eid->matrix))]
    (print-assigned-values graph potato-eaters-label)
    (print-assigned-values graph potato-eaters-mask)
    (print-assigned-values graph creator-of)
    (print-assigned-values graph creator-of-fname)))

(def ^:const lubm-triples-resource "lubm/University0_0.ntriples")
(def ^:const watdiv-triples-resource "watdiv/watdiv.10M.nt")

(comment
  (matrix/r-query
   lg
   (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
     '{:find [x y z]
       :where [[x :rdf/type :ub/GraduateStudent]
               [y :rdf/type :ub/AssistantProfessor]
               [z :rdf/type :ub/GraduateCourse]
               [x :ub/advisor y]
               [y :ub/teacherOf z]
               [x :ub/takesCourse z]]}))

  #{[:http://www.Department0.University0.edu/GraduateStudent76
     :http://www.Department0.University0.edu/AssistantProfessor5
     :http://www.Department0.University0.edu/GraduateCourse46]
    [:http://www.Department0.University0.edu/GraduateStudent143
     :http://www.Department0.University0.edu/AssistantProfessor8
     :http://www.Department0.University0.edu/GraduateCourse53]
    [:http://www.Department0.University0.edu/GraduateStudent60
     :http://www.Department0.University0.edu/AssistantProfessor8
     :http://www.Department0.University0.edu/GraduateCourse52]})

;; Bitmap-per-Row version.

;; NOTE: toString on Int2ObjectHashMap seems to be broken. entrySet
;; also seems to be behaving unexpectedly (returns duplicated keys),
;; at least when using RoaringBitmaps as values.
(defn new-r-bitmap
  (^Int2ObjectHashMap []
   (Int2ObjectHashMap.))
  (^Int2ObjectHashMap [^long size]
   (Int2ObjectHashMap. (Math/ceil (/ size 0.9)) 0.9)))

(defn new-r-roaring-bitmap ^org.roaringbitmap.buffer.MutableRoaringBitmap []
  (MutableRoaringBitmap.))

(defn r-and
  ([a] a)
  ([^Int2ObjectHashMap a ^Int2ObjectHashMap b]
   (let [ks (doto (HashSet. (.keySet a))
              (.retainAll (.keySet b)))]
     (reduce (fn [^Int2ObjectHashMap m k]
               (let [k (int k)]
                 (doto m
                   (.put k (ImmutableRoaringBitmap/and (.get a k) (.get b k))))))
             (new-r-bitmap (count ks))
             ks))))

(defn r-roaring-and
  ([a] a)
  ([^ImmutableRoaringBitmap a ^ImmutableRoaringBitmap b]
   (ImmutableRoaringBitmap/and a b)))

(defn r-cardinality ^long [^Int2ObjectHashMap a]
  (->> (.values a)
       (map #(.getCardinality ^ImmutableBitmapDataProvider %))
       (reduce +)))

(def ^Map pred-cardinality-cache (HashMap.))

;; TODO: Get rid of this or move to protocol.
(defn r-pred-cardinality [a attr]
  (.computeIfAbsent pred-cardinality-cache
                    [(System/identityHashCode a) attr]
                    (reify Function
                      (apply [_ k]
                        (r-cardinality a)))))

(defn r-diagonal-matrix [diag]
  (reduce
   (fn [^Int2ObjectHashMap m d]
     (let [d (int d)]
       (doto m
         (.put d (doto (new-r-roaring-bitmap)
                   (.add d))))))
   (new-r-bitmap (count diag))
   diag))

(defn r-transpose [^Int2ObjectHashMap a]
  (loop [i (.iterator (.keySet a))
         m ^Int2ObjectHashMap (new-r-bitmap (.size a))]
    (if-not (.hasNext i)
      m
      (let [row (.nextInt i)
            cols ^ImmutableRoaringBitmap (.get a row)]
        (.forEach cols
                  (reify IntConsumer
                    (accept [_ col]
                      (let [^MutableRoaringBitmap x (.computeIfAbsent m col
                                                                      (reify IntFunction
                                                                        (apply [_ _]
                                                                          (new-r-roaring-bitmap))))]
                        (.add x row)))))
        (recur i m)))))

(defn r-matlab-any ^org.roaringbitmap.buffer.ImmutableRoaringBitmap [^Int2ObjectHashMap a]
  (if (.isEmpty a)
    (new-r-roaring-bitmap)
    (ImmutableRoaringBitmap/or (.iterator (.values a)))))

(defn r-matlab-any-transpose ^org.roaringbitmap.buffer.ImmutableRoaringBitmap [^Int2ObjectHashMap a]
  (loop [i (.iterator (.keySet a))
         acc (new-r-roaring-bitmap)]
    (if-not (.hasNext i)
      acc
      (recur i (doto acc
                 (.add (.nextInt i)))))))

(defn r-mult-diag [^ImmutableRoaringBitmap diag ^Int2ObjectHashMap a]
  (cond (.isEmpty a)
        (new-r-bitmap)

        (nil? diag)
        a

        :else
        (doto ^MutableRoaringBitmap
            (reduce
             (fn [^Int2ObjectHashMap m d]
               (let [d (int d)]
                 (if-let [b (.get a d)]
                   (doto m
                     (.put d b))
                   m)))
             (new-r-bitmap (.getCardinality diag))
             (.toArray diag)))))

(defn r-literal-e [{:keys [value->id p->so]} a e]
  (or (some->>  (get value->id e) (int) (.get ^Int2ObjectHashMap (get p->so a)))
      (new-r-roaring-bitmap)))

(defn r-literal-v [{:keys [value->id p->os]} a v]
  (or (some->> (get value->id v) (int) (.get ^Int2ObjectHashMap (get p->os a)))
      (new-r-roaring-bitmap)))

(defn r-join [{:keys [p->os p->so] :as graph} a ^ImmutableRoaringBitmap mask-e ^ImmutableRoaringBitmap mask-v]
  (let [result (r-mult-diag
                mask-e
                (if mask-v
                  (r-transpose
                   (r-mult-diag
                    mask-v
                    (get p->os a)))
                  (get p->so a)))]
    [result
     (r-matlab-any-transpose result)
     (r-matlab-any result)]))

(def logic-var? symbol?)
(def literal? (complement logic-var?))

(def ^Map r-query-cache (HashMap.))

(defn r-compile-query [{:keys [value->id p->so] :as graph} q]
  (let [{:keys [find where]} (s/conform :crux.query/query q)
        triple-clauses (->> where
                            (filter (comp #{:triple} first))
                            (map second))
        literal-clauses (for [{:keys [e v] :as clause} triple-clauses
                              :when (or (literal? e)
                                        (literal? v))]
                          clause)
        literal-vars (->> (mapcat (juxt :e :v) literal-clauses)
                          (filter logic-var?)
                          (set))
        clauses-in-cardinality-order (->> triple-clauses
                                          (remove (set literal-clauses))
                                          (sort-by (fn [{:keys [a]}]
                                                     (r-pred-cardinality (get p->so a) a)))
                                          (vec))
        clauses-in-join-order (loop [[{:keys [e v] :as clause} & clauses] clauses-in-cardinality-order
                                     order []
                                     vars #{}]
                                (if-not clause
                                  order
                                  (if (or (empty? vars)
                                          (seq (set/intersection vars (set [e v]))))
                                    (recur clauses (conj order clause) (into vars [e v]))
                                    (recur (concat clauses [clause]) order vars))))
        var-access-order (->> (for [{:keys [e v]} clauses-in-join-order]
                                [e v])
                              (apply concat literal-vars)
                              (distinct)
                              (vec))
        _ (when-not (set/subset? (set find) (set var-access-order))
            (throw (IllegalArgumentException.
                    "Cannot calculate var access order, does the query form a single connected graph?")))
        var->mask (->> (for [{:keys [e a v]} literal-clauses]
                         (merge
                          (when (literal? e)
                            {v (r-literal-e graph a e)})
                          (when (literal? v)
                            {e (r-literal-v graph a v)})))
                       (apply merge-with r-and))
        initial-result (->> (for [[v bs] var->mask]
                              {v {v (r-diagonal-matrix (.toArray ^ImmutableRoaringBitmap bs))}})
                            (apply merge-with merge))
        var-result-order (mapv (zipmap var-access-order (range)) find)]
    [var->mask initial-result clauses-in-join-order var-access-order var-result-order]))

(defn r-query [{:keys [^IntFunction id->value] :as graph} q]
  (let [[var->mask
         initial-result
         clauses-in-join-order
         var-access-order
         var-result-order] (.computeIfAbsent r-query-cache
                                             [(System/identityHashCode graph) q]
                                             (reify Function
                                               (apply [_ k]
                                                 (r-compile-query graph q))))]
    (loop [idx 0
           var->mask var->mask
           result initial-result]
      (if-let [{:keys [e a v] :as clause} (get clauses-in-join-order idx)]
        (let [[join-result mask-e mask-v] (r-join
                                           graph
                                           a
                                           (get var->mask e)
                                           (get var->mask v))]
          (if (empty? join-result)
            #{}
            (recur (inc idx)
                   (assoc var->mask e mask-e v mask-v)
                   (update-in result [e v] (fn [bs]
                                             (if bs
                                               (r-and bs join-result)
                                               join-result))))))
        (let [[root-var] var-access-order
              seed (->> (concat
                         (for [[v bs] (get result root-var)]
                           (r-matlab-any-transpose bs))
                         (for [[e vs] result
                               [v bs] vs
                               :when (= v root-var)]
                           (r-matlab-any bs)))
                        (reduce r-roaring-and))
              transpose-cache (IdentityHashMap.)]
          (->> ((fn step [^ImmutableRoaringBitmap xs [var & var-access-order] parent-vars ^Object2IntHashMap ctx]
                  (when (and xs (not (.isEmpty xs)))
                    (let [acc (ArrayList.)
                          parent-var+plan (when var
                                            (reduce
                                             (fn [^ArrayList acc p]
                                               (let [a (get-in result [p var])
                                                     b (when-let [b (get-in result [var p])]
                                                         (.computeIfAbsent transpose-cache
                                                                           b
                                                                           (reify Function
                                                                             (apply [_ b]
                                                                               (cond-> (r-transpose b)
                                                                                 a (r-and a))))))
                                                     plan (or b a)]
                                                 (cond-> acc
                                                   plan (.add [p plan]))
                                                 acc))
                                             (ArrayList.)
                                             parent-vars))
                          parent-var (last parent-vars)]
                      (.forEach xs
                                (reify IntConsumer
                                  (accept [_ x]
                                    (if-not var
                                      (.add acc [(.apply id->value x)])
                                      (when-let [xs (reduce
                                                     (fn [^ImmutableRoaringBitmap acc [p ^Int2ObjectHashMap plan]]
                                                       (let [xs (if (= parent-var p)
                                                                  (.get plan x)
                                                                  (let [idx (.get ctx p)]
                                                                    (when-not (= -1 idx)
                                                                      (.get plan idx))))]
                                                         (if (and acc xs)
                                                           (r-roaring-and acc xs)
                                                           xs)))
                                                     nil
                                                     parent-var+plan)]
                                        (doseq [y (step xs
                                                        var-access-order
                                                        (conj parent-vars var)
                                                        (doto ctx
                                                          (.put (last parent-vars) x)))]
                                          (.add acc (cons (.apply id->value x) y))))))))
                      (seq acc))))
                seed
                (next var-access-order)
                [root-var]
                (Object2IntHashMap. -1))
               (map #(mapv (vec %) var-result-order))
               (into #{})))))))

;; Persistence Spike, this is not how it will eventually look / work,
;; but can server queries out of memory mapped buffers in LMDB.

(comment
  (require 'crux.kv.lmdb)
  (def lm (crux.kv/open (crux.kv.lmdb.LMDBKv/create {})
                        {:db-dir "dev-storage/matrix-lmdb"}))
  (matrix/load-rdf-into-lmdb lm matrix/watdiv-triples-resource)

  (def c (read-string (slurp "test/watdiv/watdiv_crux.edn")))
  (with-open [snapshot (crux.kv/new-snapshot lm)]
    (let [total (atom 0)
          qs (remove :crux-error c)
          wg-r (matrix/lmdb->r-graph snapshot)]
      (doseq [{:keys [idx query crux-results crux-time]} qs
              :when (not (contains? #{35 67} idx))]
        (let [start (System/currentTimeMillis)
              result (count (matrix/r-query wg-r (crux.sparql/sparql->datalog query)))]
          (try
            (assert (= crux-results result) (pr-str [crux-results result]))
            (catch Throwable t
              (prn t)))
          (let [t (- (System/currentTimeMillis) start)]
            (swap! total + t))))
      (prn @total (/ @total (double (count qs)))))))

(def id->value-idx-id 0)
(def value->id-idx-id 1)
(def p->so-idx-id 2)
(def p->os-idx-id 3)

(defn- date->reverse-time-ms ^long [^Date date]
  (bit-xor (bit-not (.getTime date)) Long/MIN_VALUE))

(defn- reverse-time-ms->time-ms ^long [^long reverse-time-ms]
  (bit-xor (bit-not reverse-time-ms) Long/MIN_VALUE))

(defn with-buffer-out
  (^org.agrona.DirectBuffer [b f]
   (with-buffer-out b f true))
  (^org.agrona.DirectBuffer [b f copy?]
   (with-buffer-out b f copy? 0))
  (^org.agrona.DirectBuffer [b f copy? ^long offset]
   (let [b-out (ExpandableDirectBufferOutputStream. (or b (ExpandableDirectByteBuffer.)) offset)]
     (with-open [out (DataOutputStream. b-out)]
       (f out))
     (cond-> (UnsafeBuffer. (.buffer b-out) 0 (+ (.position b-out) offset))
       copy? (mem/copy-buffer)))))

(defn key->business-time-ms ^long [^DirectBuffer k]
  (reverse-time-ms->time-ms (.getLong k (- (mem/capacity k) Long/BYTES Long/BYTES) ByteOrder/BIG_ENDIAN)))

(defn key->transaction-time-ms ^long [^DirectBuffer k]
  (reverse-time-ms->time-ms (.getLong k (- (mem/capacity k) Long/BYTES) ByteOrder/BIG_ENDIAN)))

(defn key->row-id ^long [^DirectBuffer k]
  (.getInt k (- (mem/capacity k) Integer/BYTES Long/BYTES Long/BYTES) ByteOrder/BIG_ENDIAN))

(deftype RowIdAndKey [^int row-id ^DirectBuffer key])

(defn new-snapshot-matrix [snapshot ^Date business-time ^Date transaction-time idx-id p seek-b]
  (let [business-time-ms (.getTime business-time)
        transaction-time-ms (.getTime transaction-time)
        reverse-business-time-ms (date->reverse-time-ms business-time)
        idx-id (int idx-id)
        seek-k (with-buffer-out seek-b
                 (fn [^DataOutput out]
                   (.writeInt out idx-id)
                   (nippy/freeze-to-out! out p)))
        within-prefix? (fn [k]
                         (and k (mem/buffers=? k seek-k (mem/capacity seek-k))))]
    (with-open [i (kv/new-iterator snapshot)]
      (loop [id->row (Int2ObjectHashMap.)
             k ^DirectBuffer (kv/seek i seek-k)]
        (if (within-prefix? k)
          (let [row-id+k (loop [k k]
                           (let [row-id (int (key->row-id k))]
                             (if (<= (key->business-time-ms k) business-time-ms)
                               (RowIdAndKey. row-id k)
                               (let [found-k ^DirectBuffer (kv/seek i (with-buffer-out seek-b
                                                                        (fn [^DataOutput out]
                                                                          (.writeInt out row-id)
                                                                          (.writeLong out reverse-business-time-ms))
                                                                        false
                                                                        (.capacity seek-k)))]
                                 (when (within-prefix? found-k)
                                   (let [found-row-id (int (key->row-id found-k))]
                                     (if (= row-id found-row-id)
                                       (RowIdAndKey. row-id k)
                                       (recur found-k))))))))
                row-id (.row-id ^RowIdAndKey row-id+k)
                row-id+k (loop [k (.key ^RowIdAndKey row-id+k)]
                           (when (within-prefix? k)
                             (if (<= (key->transaction-time-ms k) transaction-time-ms)
                               (RowIdAndKey. row-id k)
                               (let [next-k (kv/next i)]
                                 (if (= row-id (int (key->row-id next-k)))
                                   (recur next-k)
                                   (RowIdAndKey. -1 next-k))))))
                row-id (.row-id ^RowIdAndKey row-id+k)]
            (if (not= -1 row-id)
              (let [row (ImmutableRoaringBitmap. (.byteBuffer ^DirectBuffer (kv/value i)))]
                (recur (doto id->row
                         (.put row-id row))
                       (kv/seek i (with-buffer-out seek-b
                                    (fn [^DataOutput out]
                                      (.writeInt out (inc row-id)))
                                    false
                                    (.capacity seek-k)))))
              (recur id->row (.key ^RowIdAndKey row-id+k))))
          id->row)))))

(deftype SnapshotValueToId [snapshot ^Map cache seek-b]
  ILookup
  (valAt [this k]
    (.valAt this k nil))

  (valAt [_ k default]
    (.computeIfAbsent cache
                      k
                      (reify Function
                        (apply [_ k]
                          (with-open [i (kv/new-iterator snapshot)]
                            (let [seek-k (with-buffer-out seek-b
                                           (fn [^DataOutput out]
                                             (.writeInt out value->id-idx-id)
                                             (nippy/freeze-to-out! out k))
                                           false)
                                  k (kv/seek i seek-k)]
                              (if (and k (mem/buffers=? k seek-k))
                                (.readInt (DataInputStream. (DirectBufferInputStream. (kv/value i))))
                                default))))))))

(deftype SnapshotIdToValue [snapshot ^Int2ObjectHashMap cache seek-b]
  ILookup
  (valAt [this k]
    (.valAt this k nil))

  (valAt [this k default]
    (or (.apply this (int k)) default))

  IntFunction
  (apply [_ k]
    (.computeIfAbsent cache
                      (int k)
                      (reify IntFunction
                        (apply [_ k]
                          (with-open [i (kv/new-iterator snapshot)]
                            (let [seek-k (with-buffer-out seek-b
                                           (fn [^DataOutput out]
                                             (.writeInt out id->value-idx-id)
                                             (.writeInt out k))
                                           false)
                                  k (kv/seek i seek-k)]
                              (when (and k (mem/buffers=? k seek-k))
                                (nippy/thaw-from-in! (DataInputStream. (DirectBufferInputStream. (kv/value i))))))))))))

(defn lmdb->r-graph
  ([snapshot]
   (let [now (cio/next-monotonic-date)]
     (lmdb->r-graph snapshot now now)))
  ([snapshot business-time transaction-time]
   (let [seek-b (ExpandableDirectByteBuffer.)
         matrix-cache (HashMap.)]
     {:value->id (->SnapshotValueToId snapshot (HashMap.) seek-b)
      :id->value (->SnapshotIdToValue snapshot (Int2ObjectHashMap.) seek-b)
      :p->so (reify ILookup
               (valAt [this k]
                 (.computeIfAbsent matrix-cache
                                   [p->so-idx-id k]
                                   (reify Function
                                     (apply [_ _]
                                       (new-snapshot-matrix snapshot business-time transaction-time p->so-idx-id k seek-b)))))

               (valAt [this k default]
                 (throw (UnsupportedOperationException.))))
      :p->os (reify ILookup
               (valAt [this k]
                 (.computeIfAbsent matrix-cache
                                   [p->os-idx-id k]
                                   (reify Function
                                     (apply [_ _]
                                       (new-snapshot-matrix snapshot business-time transaction-time p->os-idx-id k seek-b)))))

               (valAt [this k default]
                 (throw (UnsupportedOperationException.))))})))

(defn get-or-create-id [kv value last-id-state pending-id-state]
  (with-open [snapshot (kv/new-snapshot kv)]
    (let [seek-b (ExpandableDirectByteBuffer.)
          value->id (->SnapshotValueToId snapshot (HashMap.) seek-b)]
      (if-let [id (get value->id value)]
        [id []]
        (let [b (ExpandableDirectByteBuffer.)
              prefix-k (with-buffer-out seek-b
                         (fn [^DataOutput out]
                           (.writeInt out id->value-idx-id)))
              within-prefix? (fn [k]
                               (and k (mem/buffers=? k prefix-k (mem/capacity prefix-k))))
              ;; TODO: This is obviously a slow way to find the latest id.
              id (get @pending-id-state
                      value
                      (do (when-not @last-id-state
                            (with-open [i (kv/new-iterator snapshot)]
                              (loop [last-id (int 0)
                                     k ^DirectBuffer (kv/seek i prefix-k)]
                                (if (within-prefix? k)
                                  (recur (.getInt k Integer/BYTES ByteOrder/BIG_ENDIAN) (kv/next i))
                                  (reset! last-id-state last-id)))))
                          (swap! last-id-state inc)))]
          (swap! pending-id-state assoc value id)
          [id [[(with-buffer-out b
                  (fn [^DataOutput out]
                    (.writeInt out id->value-idx-id)
                    (.writeInt out id)))
                (with-buffer-out b
                  (fn [^DataOutput out]
                    (nippy/freeze-to-out! out value)))]
               [(with-buffer-out b
                  (fn [^DataOutput out]
                    (.writeInt out value->id-idx-id)
                    (nippy/freeze-to-out! out value)))
                (with-buffer-out b
                  (fn [^DataOutput out]
                    (.writeInt out id)))]]])))))

(defn load-rdf-into-lmdb
  ([kv resource]
   (load-rdf-into-lmdb kv resource 100000))
  ([kv resource chunk-size]
   (with-open [in (io/input-stream (io/resource resource))]
     (let [now (cio/next-monotonic-date)
           business-time now
           transaction-time now
           b (ExpandableDirectByteBuffer.)
           last-id-state (atom nil)]
       (doseq [chunk (->> (rdf/ntriples-seq in)
                          (map rdf/rdf->clj)
                          (partition-all chunk-size))]
         (with-open [snapshot (kv/new-snapshot kv)]
           (let [g (lmdb->r-graph snapshot business-time transaction-time)
                 row-cache (HashMap.)
                 get-current-row (fn [idx-id idx p id]
                                   (.computeIfAbsent row-cache
                                                     [idx-id idx p id]
                                                     (reify Function
                                                       (apply [_ _]
                                                         [(with-buffer-out b
                                                            (fn [^DataOutput out]
                                                              (.writeInt out idx-id)
                                                              (nippy/freeze-to-out! out p)
                                                              (.writeInt out id)
                                                              (.writeLong out (date->reverse-time-ms business-time))
                                                              (.writeLong out (date->reverse-time-ms transaction-time))))
                                                          (let [^Int2ObjectHashMap m (get-in g [idx p])]
                                                            (.toMutableRoaringBitmap ^ImmutableRoaringBitmap (.get m id)))]))))

                 pending-id-state (atom {})]
             (prn (count chunk))
             (->> (for [[s p o] chunk
                        :let [[^long s-id s-id-kvs] (get-or-create-id kv s last-id-state pending-id-state)
                              [^long o-id o-id-kvs] (get-or-create-id kv o last-id-state pending-id-state)]]
                    (concat s-id-kvs
                            o-id-kvs
                            [(let [[k ^MutableRoaringBitmap row] (get-current-row p->so-idx-id :p->so p s-id)]
                               [k (doto row
                                    (.add o-id))])]
                            [(let [[k ^MutableRoaringBitmap row] (get-current-row p->os-idx-id :p->os p o-id)]
                               [k (doto row
                                    (.add s-id))])]))
                  (reduce into [])
                  (into (sorted-map-by mem/buffer-comparator))
                  (map (fn [[k v]]
                         [k (if (instance? MutableRoaringBitmap v)
                              (with-buffer-out b
                                (fn [^DataOutput out]
                                  (.serialize (doto ^MutableRoaringBitmap v
                                                (.runOptimize)) out)))
                              v)]))
                  (kv/store kv)))))))))


;; Other Matrix-format related spikes:

(defn mat-mul [^doubles a ^doubles b]
  (assert (= (alength a) (alength b)))
  (let [size (alength a)
        n (bit-shift-right size 1)
        c (double-array size)]
    (dotimes [i n]
      (dotimes [j n]
        (let [row-idx (* n i)
              c-idx (+ row-idx j)]
          (dotimes [k n]
            (aset c
                  c-idx
                  (+ (aget c c-idx)
                     (* (aget a (+ row-idx k))
                        (aget b (+ (* n k) j)))))))))
    c))

(defn vec-mul [^doubles a ^doubles x]
  (let [size (alength a)
        n (bit-shift-right size 1)
        y (double-array n)]
    (assert (= n (alength x)))
    (dotimes [i n]
      (dotimes [j n]
        (aset y
              i
              (+ (aget y i)
                 (* (aget a (+ (* n i) j))
                    (aget x j))))))
    y))

;; CSR
;; https://people.eecs.berkeley.edu/~aydin/GALLA-sparse.pdf

{:nrows 6
 :row-ptr (int-array [0 2 5 6 9 12 16])
 :col-ind (int-array [0 1
                      1 3 5
                      2
                      2 4 5
                      0 3 4
                      0 2 3 5])
 :values (double-array [5.4 1.1
                        6.3 7.7 8.8
                        1.1
                        2.9 3.7 2.9
                        9.0 1.1 4.5
                        1.1 2.9 3.7 1.1])}

;; DCSC Example
{:nrows 8
 :jc (int-array [1 7 8])
 :col-ptr (int-array [1 3 4 5])
 :row-ind (int-array [6 8 4 5])
 :values (double-array [0.1 0.2 0.3 0.4])}

(defn vec-csr-mul [{:keys [^long nrows ^ints row-ptr ^ints col-ind ^doubles values] :as a} ^doubles x]
  (let [n nrows
        y (double-array n)]
    (assert (= n (alength x)))
    (dotimes [i n]
      (loop [j (aget row-ptr i)
             yr 0.0]
        (if (< j (aget row-ptr (inc i)))
          (recur (inc i)
                 (+ yr
                    (* (aget values j)
                       (aget x (aget col-ind j)))))
          (aset y i yr))))
    y))

(ns petrglad.common.maps)

(defn map-vals [f m]
  (reduce-kv (fn [m k v]
               (assoc m k (f v)))
    (empty m) m))

(defn key-set [m]
  (into #{} (keys m)))

(defn ensure-key [m k]
  (update m k #(if % % #{})))

(defn add-assoc [m k v]
  (-> (ensure-key m k)
    (update k conj v)))

(defn reverse-dependencies [m]
  (reduce-kv
    (fn [m1 k v]
      (reduce #(add-assoc %1 %2 k)
        (ensure-key m1 k) v)) ;; So "bottom" is not lost
    {} m))


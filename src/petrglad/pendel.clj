(ns petrglad.pendel
  (:require [clojure.set :refer [intersection union difference]]
            [clojure.data.priority-map :refer [priority-map-keyfn]]))

(def component-defaults
  {:requires #{}
   :status   :init
   :start    (fn [co _deps] co)
   :stop     (fn [co] co)
   :get      identity
   :this     nil})

(defn map-vals [f m]
  (reduce-kv (fn [m k v]
               (assoc m k (f v)))
    (empty m)
    m))

(defn key-set [m]
  (into #{} (keys m)))

(defn add-assoc
  ([m k]
   (update m k #(if % % #{})))
  ([m k v]
   (update m k #(if % (conj % v) #{v}))))

(defn reverse-dependencies [m]
  (reduce-kv
    (fn [m1 k v]
      (reduce #(add-assoc %1 %2 k)
              (add-assoc m1 k) v)) ;; So "bottom" is not lost
    {} m))

(defn dependencies [system]
  (map-vals #(-> % :requires (into #{})) system))

(defn all-requires [system required-ids]
  (let [deps (dependencies system)]
    (println "Resolving" deps required-ids)
    (loop [result (select-keys deps required-ids)]
      (let [more-ids (difference (into #{} (mapcat second result))
                       (key-set result))]
        (when-let [unsatisfied (seq (difference more-ids (key-set deps)))]
          (throw (ex-info
                   "Unknown component ids."
                   {:components  result
                    :unknown-ids unsatisfied})))
        (if (seq more-ids)
          (recur (merge result (select-keys deps more-ids)))
          result)))))

(defn start-component [co deps]
  (println "Starting" co deps)
  (assoc co :this ((:start co) (:this co) deps)
            :status :started))

(defn stop-component [co]
  (println "Stopping" co)
  (assoc co :this ((:stop co) (:this co))
            :status :stopped))

(defn start
  "Starts system"
  [system required-ids]
  (let [normalized (map-vals #(merge component-defaults %) system)
        requires (all-requires normalized required-ids)]
    (loop [result normalized
           started-values {}
           to-be-started (into (priority-map-keyfn count) requires)]
      (println "To be started" to-be-started)
      (if (seq to-be-started)
        (let [[co-id deps] (peek to-be-started)]
          (when-not (empty? deps)
            (throw (ex-info
                     "Dependency cycle."
                     {:components    result
                      :to-be-started to-be-started})))
          (let [started (start-component (get result co-id)
                          (select-keys started-values (get requires co-id)))]
            (recur (assoc result co-id started)
              (assoc started-values co-id ((:get started) (:this started)))
              (map-vals #(disj % co-id) (pop to-be-started)))))
        result))))

(defn stop
  "Stops system"
  [system]
  (let [requires (dependencies system) ;; TODO (implementation) Select only started components
        provides (reverse-dependencies requires)]
    (loop [result system
           queue (into (priority-map-keyfn count) provides)]
      (println "To be stopped" queue)
      (if (seq queue)
        (let [[co-id deps] (peek queue)]
          (when-not (empty? deps)
            (throw (ex-info
                     "Dependency cycle."
                     {:components result
                      :queue      queue})))
          (recur (assoc result co-id (stop-component (get result co-id)))
                 (map-vals #(disj % co-id) (pop queue))))
        result))))

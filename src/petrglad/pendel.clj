(ns petrglad.pendel
  (:require [clojure.set :refer [intersection union difference]]
            [clojure.data.priority-map :refer [priority-map-keyfn]]))

(def statuses #{:init :started})

(def component-defaults
  {:requires #{}
   :status   :init
   :start    identity
   :stop     identity
   :get      :value
   :value    nil})

(defn map-vals [f m]
  (reduce-kv (fn [m k v]
               (assoc m k (f v)))
             (empty m)
             m))

(defn key-set [m]
  (into #{} (keys m)))

(defn all-requires [system required-ids]
  (let [deps (map-vals #(-> % :requires (into #{})) system)]
    (println "RESOLVE:" deps required-ids)
    (loop [result (select-keys deps required-ids)]
      (let [more-ids (->> (mapcat second result)
                            (into #{}))]
        (when-let [unsatisfied (seq (difference more-ids (key-set deps)))]
          (throw (ex-info
                   "Unknown component ids."
                   {:components  result
                    :unknown-ids unsatisfied})))
        (if (seq more-ids)
          (recur (merge result (select-keys deps more-ids)))
          result)))))

(defn start-component [co]
  (println "Starting" co)
  (-> ((:start co))
      (assoc :status :started)))

(defn start
  "Starts system"
  [system required-ids]
  (loop [result (map-vals #(merge component-defaults %) system)
         to-be-started (all-requires result required-ids)]
    (if (seq to-be-started)
      (let [[co-id deps] (peek to-be-started)]
        (when-not (empty? deps)
          (throw (ex-info
                   "Dependency cycle."
                   {:components    result
                    :to-be-started to-be-started})))
        (recur (update result co-id start-component)
               (map-vals #(disj % co-id) to-be-started)))
      result)))

(defn stop
  "Stops system"
  [system]
  (println "STOP <<stub>>" system))

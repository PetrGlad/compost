(ns petrglad.pendel
  (:require [clojure.set :refer [intersection union difference]]
            [clojure.data.priority-map :refer [priority-map-keyfn]]))

(def statuses #{:init :started})

(def component-defaults
  {:requires #{}
   :status   :init
   :start    (fn [co _deps] co)
   :stop     (fn [co _deps] co)
   :get      identity
   :this     nil})

(defn map-vals [f m]
  (reduce-kv (fn [m k v]
               (assoc m k (f v)))
             (empty m)
             m))

(defn key-set [m]
  (into #{} (keys m)))

(defn all-requires [system required-ids]
  (let [deps (map-vals #(-> % :requires (into #{})) system)]
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
  (println "STOP <<stub>>" system))

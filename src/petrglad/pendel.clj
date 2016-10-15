(ns petrglad.pendel
  (:require [clojure.set :refer [intersection union difference]]
            [clojure.data.priority-map :refer [priority-map-keyfn]]
            [petrglad.common :refer :all])
  (:import (org.slf4j Logger LoggerFactory)))

(def log (LoggerFactory/getLogger (name (ns-name *ns*))))

(def component-defaults
  {:requires #{}
   :status   :init
   :start    (fn [co _deps] co)
   :stop     (fn [co] co)
   :get      identity
   :this     nil})

(defn dependencies [system]
  (map-vals #(-> % :requires (into #{})) system))

(defn all-requires [system required-ids]
  (let [deps (dependencies system)]
    (.debug log "Resolving {} with {}" required-ids deps)
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
  (.debug log "Starting {} {}" co deps)
  (assoc co :this ((:start co) (:this co) deps)
            :status :started))

(defn stop-component [co]
  (.debug log "Starting {}" co)
  (assoc co :this ((:stop co) (:this co))
            :status :stopped))

(defn start
  "Starts the system"
  [system required-ids]
  (let [normalized (map-vals #(merge component-defaults %) system)
        requires (all-requires normalized required-ids)]
    (loop [result normalized
           started-values {}
           queue (into (priority-map-keyfn count) requires)]
      (.debug log "To be started {}" queue)
      (if (seq queue)
        (let [[co-id deps] (peek queue)]
          (when-not (empty? deps)
            (throw (ex-info
                     "Dependency cycle."
                     {:components    result
                      :to-be-started queue})))
          (let [started (start-component (get result co-id)
                          (select-keys started-values (get requires co-id)))]
            (recur (assoc result co-id started)
              (assoc started-values co-id ((:get started) (:this started)))
              (map-vals #(disj % co-id) (pop queue)))))
        result))))

(defn stop
  "Stops the system"
  [system]
  (let [requires (dependencies system) ;; TODO (implementation) Select only started components
        provides (reverse-dependencies requires)]
    (loop [result system
           queue (into (priority-map-keyfn count) provides)]
      (.info log "To be stopped {}" queue)
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

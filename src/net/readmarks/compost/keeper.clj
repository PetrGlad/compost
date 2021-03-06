(ns net.readmarks.compost.keeper
  "System lifecycle errors handling."
  (:require [net.readmarks.compost :as compost])
  (:import (clojure.lang ExceptionInfo)))

(defn try-update
  "Handles system's update exceptions to make sure up-to-date value of system is not lost.
  Returns {:system new-system-value :errors [exception]}"
  [system update-fn]
  (try
    {:system (update-fn system)
     :errors []}
    (catch ExceptionInfo ex
      {:system (or (compost/ex-system ex)
                 system)
       :errors [ex]})
    (catch Exception ex
      ;; Warrany is void :) The system and agent may need to be re-created.
      {:system system
       :errors [ex]})))

(defn init [system]
  {:system system})

(defn clear [sys]
  (assoc sys :errors []))

(defn update-system [{prev-sys :system prev-exs :errors} update-fn]
  (let [{s :system exs :errors} (update-fn prev-sys)]
    {:system s
     :errors (into prev-exs exs)}))

(defn keeper [system]
  (agent (init system)))

(defn update-keeper! [k update-fn]
  {:pre [(not (agent-error k))]}
  (send-off k
    update-system #(try-update % update-fn)))

(defn flush-errors! [k handle-errors!]
  {:pre [(not (agent-error k))]}
  (send-off k
    (fn [sys-ex]
      (handle-errors! (:errors sys-ex))
      (clear sys-ex))))

(ns lens.parent-resolver
  "Component which resolves parents of ODM entities like studies of form-defs."
  (:use plumbing.core)
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [lens.event-bus :as bus]))

(defrecord ParentResolver [warehouse-bus parse-bus res-topic topic callback]
  component/Lifecycle
  (start [this]
    (->> (callback warehouse-bus)
         (bus/wait-for warehouse-bus parse-bus res-topic topic)
         (assoc this :stop-fn)))
  (stop [this]
    ((:stop-fn this))
    (dissoc this :stop-fn)))

(defn parent-resolver [res-topic topic callback]
  (map->ParentResolver {:res-topic res-topic :topic topic :callback callback}))

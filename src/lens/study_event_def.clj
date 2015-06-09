(ns lens.study-event-def
  (:use plumbing.core)
  (:require [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.parent-resolver :refer [parent-resolver]]))

(defn- study-event-def-with-study-handler [bus]
  (fn [study study-event-def]
    (->> (api/upsert-study-event-def! study study-event-def)
         (bus/publish-from! bus :study-event-def))))

(defn study-event-def-importer []
  (parent-resolver :study :study-event-def study-event-def-with-study-handler))

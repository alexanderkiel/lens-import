(ns lens.item-group-def
  (:use plumbing.core)
  (:require [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.parent-resolver :refer [parent-resolver]]))

(defn- item-group-def-with-study-handler [bus]
  (fn [study item-group-def]
    (->> (api/upsert-item-group-def! study item-group-def)
         (bus/publish-from! bus :item-group-def))))

(defn item-group-def-importer []
  (parent-resolver :study :item-group-def item-group-def-with-study-handler))

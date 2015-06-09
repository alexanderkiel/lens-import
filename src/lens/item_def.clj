(ns lens.item-def
  (:use plumbing.core)
  (:require [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.parent-resolver :refer [parent-resolver]]))

(defn- item-def-props [m]
  (select-keys m [:id :name :data-type :description :question]))

(defn- item-def-with-study-handler [bus]
  (fn [study item-def]
    (->> (item-def-props item-def)
         (api/upsert-item-def! study)
         (bus/publish-from! bus :item-def))))

(defn item-def-importer []
  (parent-resolver :study :item-def item-def-with-study-handler))

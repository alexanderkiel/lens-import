(ns lens.item-ref
  (:use plumbing.core)
  (:require [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.ref-resolver :refer [ref-resolver]]))

(defn- item-ref-handler [bus]
  (fn [item-group-def item-ref _]
    (->> (api/create-item-ref! item-group-def item-ref)
         (bus/publish-from! bus :item-ref))))

(defn- def-pred [item-ref item-def]
  {:pre [(:item-id item-ref)]}
  (= (:item-id item-ref) (:id (:data item-def))))

(defn item-ref-importer []
  (ref-resolver "item-group" "item" def-pred item-ref-handler))

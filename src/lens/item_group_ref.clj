(ns lens.item-group-ref
  (:use plumbing.core)
  (:require [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.ref-resolver :refer [ref-resolver]]))

(defn- item-group-ref-handler [bus]
  (fn [form-def item-group-ref _]
    (->> (api/create-item-group-ref! form-def item-group-ref)
         (bus/publish-from! bus :item-group-ref))))

(defn- def-pred [item-group-ref item-group-def]
  {:pre [(:item-group-id item-group-ref)]}
  (= (:item-group-id item-group-ref) (:id (:data item-group-def))))

(defn item-group-ref-importer []
  (ref-resolver "form" "item-group" def-pred item-group-ref-handler))

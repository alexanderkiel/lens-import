(ns lens.item-ref
  (:use plumbing.core)
  (:require [clojure.tools.logging :as log]
            [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.ref-resolver :refer [ref-resolver]]))

(defn- item-ref-handler [bus]
  (fn [item-group-def item-ref item-def]
    (log/debug "Can handle item-ref from" (:id (:data item-group-def))
               "to" (:item-id item-ref) "and" (:id (:data item-def)))))

(defn- def-pred [item-ref item-def]
  {:pre [(:item-id item-ref)]}
  (= (:item-id item-ref) (:id (:data item-def))))

(defn item-ref-importer []
  (ref-resolver "item-group" "item" def-pred item-ref-handler))

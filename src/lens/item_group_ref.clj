(ns lens.item-group-ref
  (:use plumbing.core)
  (:require [clojure.tools.logging :as log]
            [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.ref-resolver :refer [ref-resolver]]))

(defn- item-group-ref-handler [bus]
  (fn [form-def item-group-ref item-group-def]
    (log/debug "Can handle item-group-ref from" (:id form-def)
               "to" (:item-group-id item-group-ref)
               "and" (:id item-group-def))))

(defn- def-pred [item-group-ref item-group-def]
  (= (:item-group-id item-group-ref) (:id item-group-def)))

(defn item-group-ref-importer []
  (ref-resolver "form" "item-group" def-pred item-group-ref-handler))

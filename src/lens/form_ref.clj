(ns lens.form-ref
  (:use plumbing.core)
  (:require [clojure.tools.logging :as log]
            [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.ref-resolver :refer [ref-resolver]]))

(defn- form-ref-handler [bus]
  (fn [study-event-def form-ref form-def]
    (log/debug "Can handle form-ref from" (:id (:data study-event-def))
               "to" (:form-id form-ref) "and" (:id (:data form-def)))))

(defn- def-pred [form-ref form-def]
  {:pre [(:form-id form-ref)]}
  (= (:form-id form-ref) (:id (:data form-def))))

(defn form-ref-importer []
  (ref-resolver "study-event" "form" def-pred form-ref-handler))

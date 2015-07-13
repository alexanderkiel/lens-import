(ns lens.form-ref
  (:use plumbing.core)
  (:require [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.ref-resolver :refer [ref-resolver]]))

(defn- form-ref-handler [bus]
  (fn [study-event-def form-ref _]
    (->> (api/create-form-ref! study-event-def form-ref)
         (bus/publish-from! bus :form-ref))))

(defn- def-pred [form-ref form-def]
  {:pre [(:form-id form-ref)]}
  (= (:form-id form-ref) (:id (:data form-def))))

(defn form-ref-importer []
  (ref-resolver "study-event" "form" def-pred form-ref-handler))

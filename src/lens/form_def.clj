(ns lens.form-def
  (:use plumbing.core)
  (:require [com.stuartsierra.component :as component]
            [lens.api :as api]
            [lens.event-bus :as bus]))

(defn publish-odm-form-def-with-study! [bus odm-form-def study]
  (->> (assoc odm-form-def :study study)
       (bus/publish! bus :odm-form-def-with-study)))

(defn pending-odm-form-defs-handler
  "Returns a function which reduces a seq of pending ODM form defs by publishing
  every resolvable one under the topic :odm-form-def-with-study."
  [bus {:keys [id] :as study}]
  (fn [pending-odm-form-defs]
    (reduce
      (fn [remaining-pending-odm-form-defs odm-form-def]
        (if (= id (:study-id odm-form-def))
          (do
            (publish-odm-form-def-with-study! bus odm-form-def study)
            remaining-pending-odm-form-defs)
          (conj remaining-pending-odm-form-defs odm-form-def)))
      []
      pending-odm-form-defs)))

(defn study-handler [bus]
  "Returns a function which handles messages of topic :study.

  State is a map of :studies and :pending-odm-form-defs. Collects studies for
  later use. Tries to publish ODM form defs with studies on topic
  :odm-form-def-with-study."
  (fn [state {:keys [id] :as study}]
    (-> (update-in state [:studies] #(assoc % id study))
        (update-in [:pending-odm-form-defs]
                   (pending-odm-form-defs-handler bus study)))))

(defn odm-form-def-handler
  "Returns a function which handles mesages of topic :form-def.

  State is a map of :study-uris and :pending-form-defs. Publishes form defs with
  already known study URIs to :form-def-with-study-uri. Collects the form def
  under :pending-form-defs otherwise."
  [bus]
  (fn [{:keys [studies] :as state}
       {:keys [study-id] :as form-def}]
    (if-let [study-uri (studies study-id)]
      (do
        (publish-odm-form-def-with-study! bus form-def study-uri)
        state)

      (update-in state [:pending-odm-form-defs] #(conj % form-def)))))

(defrecord FormDefStudyUriCoordinator [bus]
  component/Lifecycle
  (start [this]
    (->> (bus/listen-on-mult bus
           {:study (study-handler bus)
            :odm-form-def (odm-form-def-handler bus)}
           {:studies {}
            :pending-odm-form-defs []})
         (assoc this :stop-fn)))
  (stop [this]
    ((:stop-fn this))
    (dissoc this :stop-fn)))

(defn form-def-study-uri-coordinator []
  (map->FormDefStudyUriCoordinator {}))

(defn odm-form-def-with-study-handler [bus]
  (fnk [:as odm-form-def-with-study]
    (api/create-or-update-form-def! odm-form-def-with-study)))

(defrecord FormDefImporter [bus]
  component/Lifecycle
  (start [this]
    (->> (bus/listen-on bus :odm-form-def-with-study
           (odm-form-def-with-study-handler bus))
         (assoc this :stop-fn)))
  (stop [this]
    ((:stop-fn this))
    (dissoc this :stop-fn)))

(defn form-def-importer []
  (map->FormDefImporter {}))

(ns lens.parse
  (:use plumbing.core)
  (:require [clojure.data.xml :as xml]
            [clojure.data.zip.xml :refer [xml-> xml1-> attr attr= text]]
            [clojure.tools.logging :as log]
            [clojure.zip :as zip]
            [lens.event-bus :refer [publish!!]]))

(defn oid [loc]
  (xml1-> loc (attr :OID)))

(defn first-translated-text []
  (fn [loc] (xml1-> loc :TranslatedText text)))

(defn description [loc]
  (xml1-> loc :Description (first-translated-text)))

(defn question [loc]
  (xml1-> loc :Question (first-translated-text)))

;; ---- Study Event Def -------------------------------------------------------

(defn parse-study-event-def-head [study-event-def]
  (-> {:id (oid study-event-def)
       :name (xml1-> study-event-def (attr :Name))}
      (assoc-when :description (description study-event-def))))

(defn parse-study-event-def! [bus study-event-def]
  (publish!! bus :study-event-def (parse-study-event-def-head study-event-def)))

;; ---- Form Def --------------------------------------------------------------

(defn parse-form-def-head [form-def]
  (-> {:id (oid form-def)
       :name (xml1-> form-def (attr :Name))}
      (assoc-when :description (description form-def))))

(defn parse-form-def! [bus form-def]
  (publish!! bus :form-def (parse-form-def-head form-def)))

;; ---- Item Group Def --------------------------------------------------------

(defn parse-item-group-def-head [item-group-def]
  (-> {:id (oid item-group-def)
       :name (xml1-> item-group-def (attr :Name))}
      (assoc-when :description (description item-group-def))))

(defn parse-item-group-def! [bus item-group-def]
  (publish!! bus :item-group-def (parse-item-group-def-head item-group-def)))

;; ---- Item Def --------------------------------------------------------------

(defn- convert-data-type [data-type]
  ;;TODO: decamelcase
  (keyword data-type))

(defn parse-item-def-head [item-def]
  (-> {:id (oid item-def)
       :name (xml1-> item-def (attr :Name))
       :data-type (convert-data-type (xml1-> item-def (attr :DataType)))}
      (assoc-when :description (description item-def))
      (assoc-when :question (question item-def))))

(defn parse-item-def! [bus item-def]
  (publish!! bus :item-def (parse-item-def-head item-def)))

;; ---- Meta Data Version -----------------------------------------------------

(defn parse-meta-data-version! [bus meta-data-version]
  (doseq [study-event-def (xml-> meta-data-version :StudyEventDef)]
    (parse-study-event-def! bus study-event-def))
  (doseq [form-def (xml-> meta-data-version :FormDef)]
    (parse-form-def! bus form-def))
  (doseq [item-group-def (xml-> meta-data-version :ItemGroupDef)]
    (parse-item-group-def! bus item-group-def))
  (doseq [item-def (xml-> meta-data-version :ItemDef)]
    (parse-item-def! bus item-def)))

;; ---- Study -----------------------------------------------------------------

(defn parse-study-head [study]
  {:id (oid study)
   :name (xml1-> study :GlobalVariables :StudyName text)
   :description (xml1-> study :GlobalVariables :StudyDescription text)})

(defn parse-study! [bus study]
  (log/debug "Start parsing study" (oid study))
  (publish!! bus :study (parse-study-head study))
  (doseq [meta-data-version (xml-> study :MetaDataVersion)]
    (parse-meta-data-version! bus meta-data-version)))

;; ---- Public API ------------------------------------------------------------

(defn parse!
  "Parses an ODM XML file at input and publishes various events on bus.

  The events are:
    :study           - a study with :id, :name and :description
    :study-event-def - a study-event-def with :id, :name, optional :description
                       and :study-id
    :form-def        - a form-def with :id, :name, optional :description and
                       :study-id
    :item-group-def  - an item-group-def with :id, :name, optional :description
                       and :study-id
    :item-def        - an item-def with :id, :name, :data-type, optional
                       :description and :study-id

  Returns nil after all events could be published."
  [bus input]
  (with-open [input input]
    (let [root (zip/xml-zip (xml/parse input))]
      (doseq [study (xml-> root :Study)]
        (parse-study! bus study)))))

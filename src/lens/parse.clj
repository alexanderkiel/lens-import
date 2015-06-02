(ns lens.parse
  (:require [clojure.data.xml :as xml]
            [clojure.data.zip.xml :refer [xml-> xml1-> attr text]]
            [clojure.zip :as zip]
            [lens.event-bus :refer [publish!]]))

(defn parse-form-def-head [form-def]
  {:id (xml1-> form-def (attr :OID))
   :name (xml1-> form-def (attr :Name))
   :study-id (xml1-> (-> form-def zip/up zip/up) (attr :OID))})

(defn parse-form-def! [bus form-def]
  (publish! bus :odm-form-def (parse-form-def-head form-def)))

(defn parse-meta-data-version! [bus meta-data-version]
  (doseq [form-def (xml-> meta-data-version :FormDef)]
    (parse-form-def! bus form-def)))

(defn parse-study-head [study]
  {:id (xml1-> study (attr :OID))
   :name (xml1-> study :GlobalVariables :StudyName text)
   :description (xml1-> study :GlobalVariables :StudyDescription text)})

(defn parse-study! [bus study]
  (publish! bus :odm-study (parse-study-head study))
  (doseq [meta-data-version (xml-> study :MetaDataVersion)]
    (parse-meta-data-version! bus meta-data-version)))

(defn parse!
  "Parses an ODM XML file at input and publishes various events on bus.

  The events are:
    :odm-study    - a study with :id, :name and :description
    :odm-form-def - a form def with :id, :name, optional :description and
                    :study-id

  Returns nil after all events could be published."
  [bus input]
  (with-open [input input]
    (let [root (zip/xml-zip (xml/parse input))]
      (doseq [study (xml-> root :Study)]
        (parse-study! bus study)))))

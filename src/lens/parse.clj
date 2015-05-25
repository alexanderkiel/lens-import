(ns lens.parse
  (:require [clojure.core.async :as async]
            [clojure.data.xml :as xml]
            [clojure.data.zip.xml :refer [xml-> xml1-> attr text]]
            [clojure.zip :as zip]))

(defn publish! [ch topic msg]
  (async/>!! ch {:topic topic :msg msg}))

(defn parse-form-def [ch form-def]
  (publish! ch :form
            {:id (xml1-> form-def (attr :OID))
             :name (xml1-> form-def (attr :Name))}))

(defn parse-meta-data-version [ch meta-data-version]
  (doseq [form-def (xml-> meta-data-version :FormDef)]
    (parse-form-def ch form-def)))

(defn parse-study [ch study]
  (publish! ch :study
            {:id (xml1-> study (attr :OID))
             :name (xml1-> study :GlobalVariables :StudyName text)
             :description (xml1-> study :GlobalVariables :StudyDescription text)})
  (doseq [meta-data-version (xml-> study :MetaDataVersion)]
    (parse-meta-data-version ch meta-data-version)))

(defn parse [ch input]
  (async/thread
    (with-open [input input]
      (let [root (zip/xml-zip (xml/parse input))]
        (doseq [study (xml-> root :Study)]
          (parse-study ch study))))))

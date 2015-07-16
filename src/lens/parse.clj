(ns lens.parse
  (:use plumbing.core)
  (:require [clojure.core.async :as async :refer [>!!]]
            [clojure.data.xml :as xml]
            [clojure.data.zip.xml :refer [xml-> xml1-> attr attr= text]]
            [clojure.tools.logging :as log]
            [clojure.zip :as zip]
            [lens.event-bus :refer [>!!]]))

;; ---- Private ---------------------------------------------------------------

(defn- oid [loc]
  (xml1-> loc (attr :OID)))

(defn- first-translated-text []
  (fn [loc] (xml1-> loc :TranslatedText text)))

(defn- desc [loc]
  (xml1-> loc :Description (first-translated-text)))

(defn- question [loc]
  (xml1-> loc :Question (first-translated-text)))

;; ---- Form Ref --------------------------------------------------------------

(defn parse-form-ref [form-ref]
  (-> {:type :form-ref
       :form-id (xml1-> form-ref (attr :FormOID))
       :mandatory (= "Yes" (xml1-> form-ref (attr :Mandatory)))}
      (assoc-when :order-number (xml1-> form-ref (attr :OrderNumber)))))

(defn parse-form-ref! [bus form-ref]
  (>!! bus (parse-form-ref form-ref)))

;; ---- Study Event Def -------------------------------------------------------

(defn parse-study-event-def-head [study-event-def]
  (-> {:type :study-event-def
       :id (oid study-event-def)
       :name (xml1-> study-event-def (attr :Name))}
      (assoc-when :desc (desc study-event-def))))

(defn parse-study-event-def! [bus study-event-def]
  (>!! bus (parse-study-event-def-head study-event-def))
  (doseq [form-ref (xml-> study-event-def :FormRef)]
    (parse-form-ref! bus form-ref)))

;; ---- Item Group Ref --------------------------------------------------------------

(defn parse-item-group-ref [item-group-ref]
  (-> {:type :item-group-ref
       :item-group-id (xml1-> item-group-ref (attr :ItemGroupOID))
       :mandatory (= "Yes" (xml1-> item-group-ref (attr :Mandatory)))}
      (assoc-when :order-number (xml1-> item-group-ref (attr :OrderNumber)))))

(defn parse-item-group-ref! [bus item-group-ref]
  (>!! bus (parse-item-group-ref item-group-ref)))

;; ---- Form Def --------------------------------------------------------------

(defn parse-form-def-head [form-def]
  (-> {:type :form-def
       :id (oid form-def)
       :name (xml1-> form-def (attr :Name))}
      (assoc-when :desc (desc form-def))))

(defn parse-form-def! [bus form-def]
  (>!! bus (parse-form-def-head form-def))
  (doseq [item-group-ref (xml-> form-def :ItemGroupRef)]
    (parse-item-group-ref! bus item-group-ref)))

;; ---- Item Ref --------------------------------------------------------------

(defn parse-item-ref [item-ref]
  (-> {:type :item-ref
       :item-id (xml1-> item-ref (attr :ItemOID))
       :mandatory (= "Yes" (xml1-> item-ref (attr :Mandatory)))}
      (assoc-when :order-number (xml1-> item-ref (attr :OrderNumber)))))

(defn parse-item-ref! [ch item-ref]
  (>!! ch (parse-item-ref item-ref)))

;; ---- Item Group Def --------------------------------------------------------

(defn parse-item-group-def-head [item-group-def]
  (-> {:type :item-group-def
       :id (oid item-group-def)
       :name (xml1-> item-group-def (attr :Name))}
      (assoc-when :desc (desc item-group-def))))

(defn parse-item-group-def! [bus item-group-def]
  (>!! bus (parse-item-group-def-head item-group-def))
  (doseq [item-ref (xml-> item-group-def :ItemRef)]
    (parse-item-ref! bus item-ref)))

;; ---- Item Def --------------------------------------------------------------

(defn- convert-data-type [data-type]
  ;;TODO: decamelcase
  (keyword data-type))

(defn parse-item-def-head [item-def]
  (-> {:type :item-def
       :id (oid item-def)
       :name (xml1-> item-def (attr :Name))
       :data-type (convert-data-type (xml1-> item-def (attr :DataType)))}
      (assoc-when :desc (desc item-def))
      (assoc-when :question (question item-def))))

(defn parse-item-def! [ch item-def]
  (>!! ch (parse-item-def-head item-def)))

;; ---- Meta Data Version -----------------------------------------------------

(defn parse-meta-data-version! [ch meta-data-version]
  (doseq [study-event-def (xml-> meta-data-version :StudyEventDef)]
    (parse-study-event-def! ch study-event-def))
  (doseq [form-def (xml-> meta-data-version :FormDef)]
    (parse-form-def! ch form-def))
  (doseq [item-group-def (xml-> meta-data-version :ItemGroupDef)]
    (parse-item-group-def! ch item-group-def))
  (doseq [item-def (xml-> meta-data-version :ItemDef)]
    (parse-item-def! ch item-def)))

;; ---- Study -----------------------------------------------------------------

(defn parse-study-head [study]
  {:type :study
   :id (oid study)
   :name (xml1-> study :GlobalVariables :StudyName text)
   :desc (xml1-> study :GlobalVariables :StudyDescription text)})

(defn parse-study! [ch study]
  (log/debug "Start parsing study" (oid study))
  (>!! ch (parse-study-head study))
  (doseq [meta-data-version (xml-> study :MetaDataVersion)]
    (parse-meta-data-version! ch meta-data-version)))

;; ---- Public API ------------------------------------------------------------

(defn parse!
  "Parses an ODM XML file at input and publishes various events on bus.

  The events are:
    :study           - a study with :id, :name and :desc
    :study-event-def - a study-event-def with :id, :name, optional :desc and
                       :study-id
    :form-def        - a form-def with :id, :name, optional :desc and :study-id
    :item-group-def  - an item-group-def with :id, :name, optional :desc and
                       :study-id
    :item-def        - an item-def with :id, :name, :data-type, optional :desc
                       and :study-id

  Returns nil after all events could be published."
  [input]
  (let [ch (async/chan)]
    (async/thread
      (with-open [input input]
        (let [root (zip/xml-zip (xml/parse input))]
          (doseq [study (xml-> root :Study)]
            (parse-study! ch study)))
        (async/close! ch)))
    ch))

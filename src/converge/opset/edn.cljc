;; Copyright 2020 Evident Systems LLC

;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at

;;     http://www.apache.org/licenses/LICENSE-2.0

;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns converge.opset.edn
  "API for converting an OpSet interpretation as EDN data."
  (:require [clojure.data.avl :as avl]
            [converge.core :as core]
            [converge.util :as util]
            [converge.opset.interpret :as interpret]))

#?(:clj  (set! *warn-on-reflection* true)
   :cljs (set! *warn-on-infer* true))

(defmulti -edn
  (fn [{:keys [elements entity]}]
    (some-> elements
            (get-in [entity :value])
            util/get-type))
  :default ::default)

(defmethod -edn ::default
  [{:keys [elements entity]}]
  (get-in elements [entity :value]))

(defmethod -edn :map
  [{:keys [elements list-links eavt entity]}]
  (let [attrs (avl/subrange eavt
                            >= (interpret/->Element entity nil nil)
                            <  (interpret/->Element (core/successor-id entity) nil nil))]
    (loop [i   0
           ret (transient {})
           idx (transient {})]
      (if-let [element (nth attrs i nil)]
        (let [k (-edn {:elements   elements
                       :list-links list-links
                       :eavt       eavt
                       :entity     (:attribute element)})]
          (recur (inc i)
                 (assoc! ret
                         k
                         (-edn {:elements   elements
                                :list-links list-links
                                :eavt       eavt
                                :entity     (:value element)}))
                 (assoc! idx k (:attribute element))))
        (some-> ret
                persistent!
                (vary-meta assoc
                           :converge/id entity
                           :converge/keys (persistent! idx)))))))

(defn- -edn-vector
  [elements list-links eavt entity]
  (let [attrs (persistent!
               (reduce (fn [agg {:keys [attribute value]}]
                         (assoc! agg attribute value))
                       (transient {})
                       (avl/subrange eavt
                                     >= (interpret/->Element entity nil nil)
                                     <  (interpret/->Element (core/successor-id entity) nil nil))))]
    (loop [ins (get list-links entity)
           ret (transient [])
           idx (transient [])]
      (if (= ins interpret/list-end-sigil)
        (some-> ret
                persistent!
                (vary-meta assoc
                           :converge/id entity
                           :converge/insertions (persistent! idx)))
        (let [[next-ret next-idx]
              (if-some [value (get attrs ins)]
                [(conj! ret (-edn {:elements   elements
                                   :list-links list-links
                                   :eavt       eavt
                                   :entity     value}))
                 (conj! idx ins)]
                [ret idx])]
          (recur (get list-links ins)
                 next-ret
                 next-idx))))))

(defmethod -edn :vec
  [{:keys [elements list-links eavt entity]}]
  (-edn-vector elements list-links eavt entity))

(defmethod -edn :set
  [{:keys [elements list-links eavt entity]}]
  (let [attrs (avl/subrange eavt
                            >= (interpret/->Element entity nil nil)
                            <  (interpret/->Element (core/successor-id entity) nil nil))]
    (loop [i   0
           ret (transient #{})
           idx (transient {})]
      (if-let [element (nth attrs i nil)]
        (let [member (-edn {:elements   elements
                            :list-links list-links
                            :eavt       eavt
                            :entity     (:attribute element)})]
          (recur (inc i)
                 (conj! ret member)
                 (assoc! idx member (:attribute element))))
        (some-> ret
                persistent!
                (vary-meta assoc
                           :converge/id entity
                           :converge/keys (persistent! idx)))))))

(defmethod -edn :lst
  [{:keys [elements list-links eavt entity]}]
  (let [v (-edn-vector elements list-links eavt entity)]
   (with-meta (apply list v) (meta v))))

(defn root-element-id
  [elements]
  (let [root-id (core/make-id)]
    (reduce-kv (fn [agg id {:keys [root?]}]
                 (if root?
                   (if (nat-int? (compare id agg)) id agg)
                   agg))
               root-id
               elements)))

(defn edn
  "Transforms an converge.opset.interpret.Interpretation into an EDN value."
  [{:keys [elements list-links eavt] :as _interpretation}]
  (-edn {:elements   elements
         :list-links list-links
         :eavt       (or eavt (interpret/elements->eavt elements))
         :entity     (root-element-id elements)}))

(ns somnium.congomongo.coerce
  (:use [clojure.data.json :only [json-str read-json]]
        [clojure.core.incubator :only [seqable?]])
  (:import [clojure.lang IPersistentMap IPersistentVector Keyword]
           [java.util Map Collection]
           [com.mongodb DBObject BasicDBObject BasicDBList]
           [com.mongodb.gridfs GridFSFile]
           [com.mongodb.util JSON]))

(def ^{:dynamic true
       :doc "Set this to false to prevent coercion from setting string keys to keywords"}
      *keywordize* true)


;;; Converting data from mongo into Clojure data objects

(defprotocol ConvertibleFromMongo
  (mongo->clojure [o keywordize]))

(def ^{:private true} dot-replacement "_DOT_")

(defn- to-keyword [s]
  (let [s (.replaceAll s dot-replacement ".")]
    (keyword s)))

(defn- assocs->clojure [kvs keywordize]
  ;; Taking the keywordize test out of the fn reduces derefs
  ;; dramatically, which was the main barrier to matching pure-Java
  ;; performance for this marshalling
  (reduce (if keywordize
            (fn [m [^String k v]]
              (assoc m (to-keyword k) (mongo->clojure v true)))
            (fn [m [^String k v]]
              (assoc m k (mongo->clojure v false))))
          {} (reverse kvs)))

(extend-protocol ConvertibleFromMongo
  Map
  (mongo->clojure [^Map m keywordize]
                  (assocs->clojure (.entrySet m) keywordize))

  Collection
  (mongo->clojure [^Collection l keywordize]
                  (vec (map #(mongo->clojure % keywordize) l)))

  Object
  (mongo->clojure [o keywordize] o)

  nil
  (mongo->clojure [o keywordize] o)

  BasicDBList
  (mongo->clojure [^BasicDBList l keywordize]
                  (vec (map #(mongo->clojure % keywordize) l)))

  DBObject
  (mongo->clojure [^DBObject f keywordize]
                  ;; DBObject provides .toMap, but the implementation in
                  ;; subclass GridFSFile unhelpfully throws
                  ;; UnsupportedOperationException
                  (assocs->clojure (for [k (.keySet f)] [k (.get f k)]) keywordize)))


;;; Converting data from Clojure into data objects suitable for Mongo

(defprotocol ConvertibleToMongo
  (clojure->mongo [o]))

(extend-protocol ConvertibleToMongo
  IPersistentMap
  (clojure->mongo [m] (let [dbo (BasicDBObject.)]
                        (doseq [[k v] m]
                          (.put dbo
                                (if (keyword? k)
                                  (if-let [n (namespace k)]
                                    (str (.replaceAll n "\\." dot-replacement) "/" (name k))
                                    (name k))
                                  (clojure->mongo k))
                                (clojure->mongo v)))
                        dbo))

  Keyword
  (clojure->mongo [^Keyword o] (.substring (str o) 1))

  Collection
  (clojure->mongo [^Collection o] (map clojure->mongo o))

  Object
  (clojure->mongo [o] o)

  nil
  (clojure->mongo [o] o))



(let [translations {[:clojure :mongo  ] clojure->mongo
                    [:clojure :json   ] json-str
                    [:mongo   :clojure] #(mongo->clojure ^DBObject % ^Boolean/TYPE *keywordize*)
                    [:mongo   :json   ] #(.toString ^DBObject %)
                    [:json    :clojure] #(read-json % *keywordize*)
                    [:json    :mongo  ] #(JSON/parse %)}]
  (defn coerce
    "takes an object, a vector of keywords:
     from [ :clojure :mongo :json ]
     to   [ :clojure :mongo :json ],
     and an an optional :many keyword parameter which defaults to false"
    {:arglists '([obj [:from :to] {:many false}])}
    [obj from-and-to & {:keys [many] :or {many false}}]
    (let [[from to] from-and-to]
      (cond (= from to) obj
            (nil?   to) nil
            :else       (if-let [f (translations from-and-to)]
                          (if many
                            (map f (if (seqable? obj)
                                     obj
                                     (iterator-seq obj)))
                            (f obj))
                          (throw (RuntimeException. "unsupported keyword pair")))))))

(defn ^DBObject coerce-fields
  "only used for creating argument object for :only"
  [fields]
  (clojure->mongo ^IPersistentMap (if (map? fields)
                                    (into {} (for [[k v] fields]
                                               [k (if v 1 0)]))
                                    (zipmap fields (repeat 1)))))


(defn ^DBObject coerce-index-fields
  "Used for creating index specifications.

  [:a :b :c] => (sorted-map :a 1 :b 1 :c 1)
  [:a [:b 1] :c] => (sorted-map :a 1 :b -1 :c 1)

  See also somnium.congomongo/add-index!"
  [fields]
  (clojure->mongo ^IPersistentMap (apply array-map
                                          (flatten
                                           (for [f fields]
                                             (if (vector? f)
                                               f
                                               [f 1]))))))

(ns org.clojars.aldebogdanov.quint-connect.itf
  "Decode Quint's ITF trace files into EDN. Pure: no file or process I/O.
  What Quint emits, and why parts of it are odd, is in docs/notes/itf-format.md."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def ^:private action-var "mbt::actionTaken")
(def ^:private picks-var "mbt::nondetPicks")

;; `#unserializable` is absent on purpose, and stayed absent through M7b. The
;; Apalache traces that milestone was waiting for arrived and carried none of
;; it: Quint's writer never emits it, Apalache's counterexamples have not been
;; seen to either, and only the ITF *reader* side of both accepts it. So there
;; is still nothing to decode against, and a fixture that looks about right is
;; not an option here — see CONTRIBUTING, "Fixtures are recordings".
(def ^:private known-tags #{"#bigint" "#set" "#tup" "#map"})

(defn- fail [error msg data]
  (throw (ex-info msg (assoc data :quint/error error))))

(defn- ->int
  "Long when it fits, since (= 5N 5) is false and state is compared with =."
  [^BigInteger n]
  (try (.longValueExact n)
       (catch ArithmeticException _ (bigint n))))

(defn- decode-bigint [s]
  (try (->int (BigInteger. ^String s))
       (catch NumberFormatException _
         (fail :bad-itf (str "#bigint is not an integer: " (pr-str s)) {:value s}))))

(defn- bignumber?
  "Strict: a genuine record can carry fields named s, e and c."
  [m]
  (let [{:strs [s e c]} m]
    (and (= #{"s" "e" "c"} (set (keys m)))
         (map? s) (contains? #{"1" "-1"} (get s "#bigint"))
         (map? e) (contains? e "#bigint")
         (vector? c) (seq c)
         (every? #(and (map? %) (contains? % "#bigint")) c))))

(defn- decode-bignumber
  "The {s, e, c} form the rust backend leaks for |n| >= 10^15. Rule and
  verification: docs/notes/itf-format.md §\"Large integers\"."
  [m]
  (let [sign   (decode-bigint (get-in m ["s" "#bigint"]))
        exp    (decode-bigint (get-in m ["e" "#bigint"]))
        chunks (map #(get % "#bigint") (get m "c"))
        digits (apply str (first chunks)
                      (map #(format "%014d" (BigInteger. ^String %)) (rest chunks)))
        scale  (- (inc exp) (count digits))]
    (when (neg? scale)
      (fail :bad-itf "bignumber exponent is smaller than its digit count"
            {:value m :exponent exp :digits (count digits)}))
    (-> (BigInteger. ^String digits)
        (.multiply (.pow BigInteger/TEN scale))
        (.multiply (BigInteger/valueOf sign))
        ->int)))

(defn- decode-value [v]
  (cond
    (map? v)
    (let [unknown (remove known-tags (filter #(str/starts-with? % "#") (keys v)))]
      (cond
        (seq unknown)
        (fail :bad-itf
              (str "unsupported ITF encoding " (pr-str (first unknown))
                   (when (= "#unserializable" (first unknown))
                     (str " — it is in the ITF specification, but no Quint or"
                          " Apalache output has been observed emitting one, so"
                          " there is nothing to decode it against. A trace that"
                          " carries one is exactly what would fix that: please"
                          " open an issue with it at "
                          "https://github.com/aldebogdanov/quint-connect-clj/issues")))
              {:value v :supported known-tags})

        (contains? v "#bigint") (decode-bigint (get v "#bigint"))
        (contains? v "#set")    (into #{} (map decode-value) (get v "#set"))
        (contains? v "#tup")    (mapv decode-value (get v "#tup"))
        (contains? v "#map")    (into {} (map (fn [[k x]] [(decode-value k) (decode-value x)]))
                                      (get v "#map"))
        (bignumber? v)          (decode-bignumber v)
        ;; Records and sum-type variants are indistinguishable by shape, so a
        ;; variant stays {:tag "Busy" :value 2}; users reshape it in a reader.
        :else (reduce-kv (fn [acc k x] (assoc acc (keyword k) (decode-value x))) {} v)))

    (vector? v) (mapv decode-value v)
    :else       v))

(defn- default-key-fn [full-name]
  (keyword (peek (str/split full-name #"::"))))

(defn- decode-picks
  "Quint's runtime wraps every pick in Some/None regardless of what the spec
  declares, so unwrapping here undoes Quint's own encoding, not the author's.
  None means the pick was never bound, so the key is dropped."
  [m]
  (reduce-kv
   (fn [acc k v]
     (case (and (map? v) (get v "tag"))
       "Some" (assoc acc (keyword k) (decode-value (get v "value")))
       "None" acc
       (fail :bad-itf (str "nondet pick " (pr-str k) " is not wrapped in Some/None")
             {:pick k :value v})))
   {} m))

(defn- decode-state [key-fn state]
  (reduce-kv
   (fn [acc k v]
     (condp = k
       "#meta"    (assoc acc :index (get v "index"))
       action-var (assoc acc :action v)
       picks-var  (assoc acc :picks (decode-picks v))
       (assoc-in acc [:state (key-fn k)] (decode-value v))))
   {:action nil :picks {} :state {}}
   state))

(defn- path!
  "A decode path is a vector of keys for `get-in`, and nothing else. A bare
  keyword is the likely mistake, so it gets said out loud."
  [opt path]
  (when-not (vector? path)
    (fail :bad-decode-path
          (str opt " must be a vector of keys, as in [:lastAction]; got " (pr-str path))
          {:option opt :path path}))
  path)

(defn- action-at
  "The action name a spec recorded for itself, from an ordinary variable."
  [{:keys [index state]} path]
  (let [v (get-in state path)]
    (cond
      (string? v) v
      (nil? v)    (fail :bad-decode-path
                        (str ":action-path " path " found nothing in state " index)
                        {:option :action-path :path path :index index
                         :vars (vec (sort (keys state)))})
      :else       (fail :bad-decode-path
                        (str ":action-path " path " found " (pr-str v) " in state " index
                             ", which is not an action name."
                             (when (:tag v) " For a sum type, end the path in :tag."))
                        {:option :action-path :path path :index index :found v}))))

(defn- picks-at
  "The picks a spec recorded for itself. Not unwrapped: `Some`/`None` is
  Quint's own encoding of `mbt::nondetPicks`, not something a spec author
  writes into an ordinary variable."
  [{:keys [index state]} path]
  (let [v (get-in state path)]
    (if (map? v)
      v
      (fail :bad-decode-path
            (str ":nondet-path " path " found " (pr-str v) " in state " index
                 ", and picks must be a record of pick name to value")
            {:option :nondet-path :path path :index index :found v}))))

(defn- roots
  "The state variables the given paths read out of."
  [& paths]
  (into [] (comp (remove nil?) (map first)) paths))

(defn- tracked
  "Take `:action` and `:picks` from ordinary state variables, for traces that
  carry no `mbt::` metadata — `quint test` and `quint verify` emit none, and a
  Choreo-style spec tracks them itself. Each path's root variable leaves
  `:state`: it is the spec's own bookkeeping, and the implementation must not
  be asked to supply it."
  [st {:keys [action-path nondet-path]}]
  (cond-> st
    action-path (assoc :action (action-at st action-path))
    nondet-path (assoc :picks (picks-at st nondet-path))
    :always     (update :state #(apply dissoc % (roots action-path nondet-path)))))

(defn- check-collisions! [key-fn full-names]
  (doseq [[k fulls] (reduce (fn [m f] (update m (key-fn f) (fnil conj #{}) f)) {} full-names)
          :when (< 1 (count fulls))]
    (fail :name-collision
          (str "two spec variables both normalize to " k ": " (str/join ", " (sort fulls)))
          {:key k :names (vec (sort fulls))})))

(defn json->itf
  "Parse ITF file contents. Takes the JSON as a string, returns the raw ITF map
  with string keys and undecoded values. Throws `ex-info` with `:quint/error`
  `:bad-itf` if it is not a JSON object."
  [s]
  (let [parsed (try (json/read-str s)
                    (catch Exception e
                      (fail :bad-itf (str "could not parse ITF JSON: " (ex-message e))
                            {:cause (ex-message e)})))]
    (if (map? parsed)
      parsed
      (fail :bad-itf "ITF must be a JSON object" {:parsed-type (type parsed)}))))

(defn itf->trace
  "Decode a parsed ITF map into a trace.

  Takes the map from `json->itf` and optionally

    :key-fn       full variable name -> keyword; defaults to the last `::`
                  segment and never sees `mbt::` names
    :action-path  path to a variable holding the action name, for traces
                  with no `mbt::actionTaken`
    :nondet-path  path to a variable holding the picks, likewise

  Names keep Quint's camelCase. Returns

    {:source \"bank.qnt\"
     :vars   [:balances :lastError]
     :states [{:index 0 :action \"init\" :picks {} :state {...}} ...]}

  `:vars` comes from the state keys, not the file's `vars` array, which Quint
  emits with duplicate `mbt::` entries. Traces without `mbt::` variables and
  without the two paths decode with `:action` nil and `:picks` empty.

  The paths are vectors for `get-in`, applied to the decoded state, and the
  variable each one starts at is not part of `:state`: the spec's own
  bookkeeping is not state the implementation has to supply. They take
  precedence over `mbt::` variables when a trace happens to carry both.

  Throws `ex-info` with `:quint/error` `:bad-itf` for a malformed file or an
  unsupported encoding, `:name-collision` when two variables normalize alike,
  `:bad-decode-path` when a path is not a vector or does not lead to an action
  name or a record of picks."
  ([itf] (itf->trace itf nil))
  ([itf opts]
   (let [key-fn (get opts :key-fn default-key-fn)
         paths  (reduce (fn [m k] (cond-> m (get opts k) (assoc k (path! k (get opts k)))))
                        {} [:action-path :nondet-path])
         states (get itf "states")]
     (when-not (vector? states)
       (fail :bad-itf "ITF has no states array" {:found (keys itf)}))
     (let [full-names (into #{} (comp (mapcat keys)
                                      (remove #{"#meta" action-var picks-var}))
                            states)
           split      (set (roots (:action-path paths) (:nondet-path paths)))]
       (check-collisions! key-fn full-names)
       {:source (get-in itf ["#meta" "source"])
        :vars   (vec (sort (remove split (map key-fn full-names))))
        :states (mapv #(tracked (decode-state key-fn %) paths) states)}))))

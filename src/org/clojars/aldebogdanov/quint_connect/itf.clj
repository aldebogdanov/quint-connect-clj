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
(def ^:private tag-shapes
  "What ITF writes under each tag, as [predicate, how to say it]. The shape is
  checked before anything decodes the payload: a corrupted `#bigint` is a
  `ClassCastException` otherwise, and a corrupted `#tup` or `#map` is worse
  still — it decodes to a mangled value that only diverges several steps later."
  {"#bigint" [string? "a string"]
   "#set"    [vector? "an array"]
   "#tup"    [vector? "an array"]
   "#map"    [#(and (vector? %)
                    (every? (fn [e] (and (vector? e) (= 2 (count e)))) %))
              "an array of two-element [key, value] arrays"]})

(def ^:private known-tags (set (keys tag-shapes)))

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
         (map? e) (string? (get e "#bigint"))
         (vector? c) (seq c)
         (every? #(and (map? %) (string? (get % "#bigint"))) c))))

(defn- decode-bignumber
  "The {s, e, c} form Quint writes for |n| >= 10^15 under --backend=rust: the
  internals of a bignumber.js object, leaked by its ITF writer rather than by
  the Rust evaluator. Rule and verification: docs/notes/itf-format.md
  §\"Large integers\"."
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

(defn- tagged!
  "The payload under a known tag, refused unless it has the shape ITF writes.
  Decoding a corrupted one is what turns a broken file into a
  `ClassCastException`, or into a value that is quietly wrong."
  [v tag]
  (let [payload    (get v tag)
        [ok? want] (get tag-shapes tag)]
    (if (ok? payload)
      payload
      (fail :bad-itf
            (str tag " carries " (pr-str payload) ", and ITF writes it as " want)
            {:tag tag :value v}))))

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

        (contains? v "#bigint") (decode-bigint (tagged! v "#bigint"))
        (contains? v "#set")    (into #{} (map decode-value) (tagged! v "#set"))
        (contains? v "#tup")    (mapv decode-value (tagged! v "#tup"))
        (contains? v "#map")    (into {} (map (fn [[k x]] [(decode-value k) (decode-value x)]))
                                      (tagged! v "#map"))
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

(defn- meta-index!
  "The index a state records for itself, out of its `#meta`. Checked rather
  than `get`-ed: a `#meta` that is not an object, or an index that is not an
  integer, used to yield nil and surface much later as a failure report about
  step nil.

  A `#meta` carrying no index at all is not an error. ITF lets a producer put
  what it likes in there, and there is no recording of one without an index to
  hold that against — every trace from `quint run`, `quint test` and
  `quint verify` carries one on every state."
  [v]
  (when-not (map? v)
    (fail :bad-itf
          (str "a state's #meta carries " (pr-str v) ", and ITF writes it as an object")
          {:value v}))
  (let [index (get v "index")]
    (when-not (or (nil? index) (integer? index))
      (fail :bad-itf
            (str "a state's #meta index is " (pr-str index)
                 ", and ITF writes it as an integer")
            {:value v :index index}))
    index))

(defn- decode-state [key-fn state]
  (reduce-kv
   (fn [acc k v]
     (condp = k
       "#meta"    (assoc acc :index (meta-index! v))
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
  writes into an ordinary variable.

  The empty tuple is no picks. A variant without an argument — Choreo's `Init`
  — carries it as its value, and it is Quint's spelling of nothing rather than
  a malformed record."
  [{:keys [index state]} path]
  (let [v (get-in state path)]
    (cond
      (map? v) v
      (= [] v) {}
      :else    (fail :bad-decode-path
                     (str ":nondet-path " path " found " (pr-str v) " in state " index
                          ", and picks must be a record of pick name to value")
                     {:option :nondet-path :path path :index index :found v}))))

(defn- state-at
  "The record a spec keeps all of its state in — Choreo's `s` — whose fields
  stand in for spec variables from here on."
  [{:keys [index state]} path]
  (let [v (get-in state path)]
    (cond
      (map? v) v
      (nil? v) (fail :bad-decode-path
                     (str ":state-path " path " found nothing in state " index
                          "; the trace's variables are "
                          (str/join ", " (map pr-str (sort (keys state)))))
                     {:option :state-path :path path :index index
                      :vars (vec (sort (keys state)))})
      :else    (fail :bad-decode-path
                     (str ":state-path " path " found " (pr-str v) " in state " index
                          ", and the state to compare must be a record")
                     {:option :state-path :path path :index index :found v}))))

(defn- roots
  "The state variables the given paths read out of."
  [& paths]
  (into [] (comp (remove nil?) (map first)) paths))

(defn- something-left!
  "Refuse paths whose roots take every variable with them. The action is read
  correctly and then nothing is compared, so every step passes — which is what
  `:action-path [:s ...]` does to a Choreo spec, whose `s` is all of its state."
  [{:keys [index state]} split set-opts]
  (when (and (seq state) (every? (set split) (keys state)))
    (fail :bad-decode-path
          (str (str/join " and " set-opts) (if (next set-opts) " start" " starts") " at "
               (str/join ", " (map pr-str (distinct split)))
               ", which is every variable in state " index
               ", so nothing would be left to compare. When one variable holds"
               " all of the state, as Choreo's s does, set :state-path [:s] and"
               " write the other paths inside it.")
          {:option (first set-opts) :index index :roots (vec (distinct split))})))

(defn- tracked
  "Take `:action` and `:picks` from ordinary state variables, for traces that
  carry no `mbt::` metadata — `quint test` and `quint verify` emit none, and a
  Choreo-style spec tracks them itself. Each path's root variable leaves
  `:state`: it is the spec's own bookkeeping, and the implementation must not
  be asked to supply it.

  `:state-path` goes first. The state becomes the record found there, and the
  other two paths are read inside it, so their roots are its fields."
  [st {:keys [state-path action-path nondet-path]}]
  (let [st    (cond-> st state-path (assoc :state (state-at st state-path)))
        split (roots action-path nondet-path)]
    (something-left! st split (cond-> [] action-path (conj :action-path)
                                     nondet-path (conj :nondet-path)))
    (cond-> st
      action-path (assoc :action (action-at st action-path))
      nondet-path (assoc :picks (picks-at st nondet-path))
      :always     (update :state #(apply dissoc % split)))))

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
    :state-path   path to a record holding all of the state, as Choreo's `s`
                  does; its fields are then the variables
    :action-path  path to a variable holding the action name, for traces
                  with no `mbt::actionTaken`
    :nondet-path  path to a variable holding the picks, likewise

  Names keep Quint's camelCase. Returns

    {:source \"bank.qnt\"
     :vars   [:balances :lastError]
     :states [{:index 0 :action \"init\" :picks {} :state {...}} ...]}

  `:vars` comes from the decoded states, not the file's `vars` array, which
  Quint emits with duplicate `mbt::` entries. Traces without `mbt::` variables
  and without the paths decode with `:action` nil and `:picks` empty.

  The paths are vectors for `get-in`, applied to the decoded state.
  `:state-path` is applied first, and replaces the state with the record it
  finds; the other two are then read inside that record. The variable each of
  those two starts at is not part of `:state`: the spec's own bookkeeping is
  not state the implementation has to supply. They take precedence over
  `mbt::` variables when a trace happens to carry both. The empty tuple at
  `:nondet-path` is no picks — the value of a variant without an argument.

  Throws `ex-info` with `:quint/error` `:bad-itf` for a malformed file or an
  unsupported encoding, `:name-collision` when two variables normalize alike,
  `:bad-decode-path` when a path is not a vector, does not lead to a record of
  state, an action name or a record of picks, or when the paths' roots would
  leave nothing to compare."
  ([itf] (itf->trace itf nil))
  ([itf opts]
   (let [key-fn (get opts :key-fn default-key-fn)
         paths  (reduce (fn [m k] (cond-> m (get opts k) (assoc k (path! k (get opts k)))))
                        {} [:state-path :action-path :nondet-path])
         states (get itf "states")]
     (when-not (vector? states)
       (fail :bad-itf "ITF has no states array" {:found (keys itf)}))
     (check-collisions! key-fn (into #{} (comp (mapcat keys)
                                               (remove #{"#meta" action-var picks-var}))
                                     states))
     (let [decoded (mapv #(tracked (decode-state key-fn %) paths) states)]
       {:source (get-in itf ["#meta" "source"])
        :vars   (vec (sort (into #{} (mapcat (comp keys :state)) decoded)))
        :states decoded}))))

package com.footballai.app.data.remote

/**
 * The id a past season's club answers to, mapped to the id the live feed uses today.
 *
 * The app reads two publishers of the same matches. The current season comes from
 * open-matchday, which keeps the names the live feed uses — `FC Bayern München`,
 * `1. FC Köln`, `Athletic Club`. The archive of earlier seasons comes from
 * football-data.co.uk, which uses its own short names — `Bayern Munich`, `FC Koln`,
 * `Athletic`. A team is identified by a normalised form of its published name, so the
 * same club ended up with two ids: the fixture carried one, every archived result the
 * other. The rating book joins on the id, so for those clubs the archive was silently
 * ignored — measured over the leagues this app can archive for, 199 of 511 current
 * team ids (39%) had no reachable history, and in the Bundesliga, La Liga, Ligue 1 and
 * the Primeira Liga it was 17-18 of every 18-20 clubs.
 *
 * How the table is built (see scripts/build_team_aliases.py): a pair is written down only
 * when the same date and the same final score appear in both publishers and that
 * combination is unique inside the league. Nothing is transliterated, shortened or
 * matched fuzzily, and a pair that could rewrite a key the live feed still uses is
 * dropped — attaching this season's results to the wrong club would be worse than
 * leaving an old name unlinked. Ambiguous rows are discarded, never guessed.
 *
 * What it deliberately does not do: it does not merge two clubs, change a displayed
 * name, or invent history. A promoted club that has never played in the division keeps
 * its honest "no history" state (that is what the 10 remaining unmatched ids are).
 *
 * Regenerate it when names change (promotions, renames):
 * `python3 scripts/build_team_aliases.py`.
 */
internal object TeamAliases {

    /** `league|archived-key=live-key`, one pair per line. Sorted by league, then source. */
    private const val RAW: String = """
        B1|beveren=waasland-beveren
        B1|cercle-brugge=cercle-brugge-ksv
        B1|charleroi=royal-charleroi-sc
        B1|genk=racing-genk
        B1|gent=kaa-gent
        B1|kortrijk=kv-kortrijk
        B1|mechelen=kv-mechelen
        B1|oud-heverlee-leuven=oh-leuven
        B1|raal-la-louviere=raal-la-louvi-re
        B1|st-gilloise=union-st-gilloise
        B1|st-truiden=sint-truidense
        B1|standard=standard-liege
        B1|waregem=zulte-waregem
        B1|westerlo=kvc-westerlo
        D1|augsburg=fc-augsburg
        D1|bayern-munich=fc-bayern-m-nchen
        D1|dortmund=borussia-dortmund
        D1|ein-frankfurt=eintracht-frankfurt
        D1|elversberg=sv-07-elversberg
        D1|fc-koln=1-fc-k-ln
        D1|freiburg=sc-freiburg
        D1|hamburg=hamburger-sv
        D1|hoffenheim=tsg-hoffenheim
        D1|leverkusen=bayer-04-leverkusen
        D1|m-gladbach=borussia-m-nchengladbach
        D1|mainz=1-fsv-mainz-05
        D1|paderborn=sc-paderborn-07
        D1|schalke-04=fc-schalke-04
        D1|stuttgart=vfb-stuttgart
        D1|union-berlin=1-fc-union-berlin
        D1|werder-bremen=sv-werder-bremen
        D2|bielefeld=dsc-arminia-bielefeld
        D2|bochum=vfl-bochum
        D2|braunschweig=eintracht-braunschweig
        D2|cottbus=energie-cottbus
        D2|darmstadt=sv-darmstadt-98
        D2|dresden=dynamo-dresden
        D2|greuther-furth=spvgg-greuther-f-rth
        D2|hannover=hannover-96
        D2|heidenheim=1-fc-heidenheim-1846
        D2|hertha=hertha-bsc
        D2|kaiserslautern=1-fc-kaiserslautern
        D2|karlsruhe=karlsruher-sc
        D2|magdeburg=1-fc-magdeburg
        D2|nurnberg=1-fc-n-rnberg
        D2|osnabruck=vfl-osnabr-ck
        D2|st-pauli=fc-st-pauli
        D2|wolfsburg=vfl-wolfsburg
        E0|man-united=man-utd
        E0|tottenham=spurs
        E1|birmingham=birmingham-city
        E1|blackburn=blackburn-rovers
        E1|bolton=bolton-wanderers
        E1|cardiff=cardiff-city
        E1|charlton=charlton-athletic
        E1|derby=derby-county
        E1|lincoln=lincoln-city
        E1|norwich=norwich-city
        E1|preston=preston-north-end
        E1|qpr=queens-park-rangers
        E1|stoke=stoke-city
        E1|swansea=swansea-city
        E1|west-brom=west-bromwich-albion
        E1|west-ham=west-ham-united
        E1|wolves=wolverhampton-wanderers
        E2|bradford=bradford-city
        E2|burton=burton-albion
        E2|cambridge=cambridge-united
        E2|doncaster=doncaster-rovers
        E2|huddersfield=huddersfield-town
        E2|leicester=leicester-city
        E2|luton=luton-town
        E2|mansfield=mansfield-town
        E2|milton-keynes-dons=mk-dons
        E2|oxford=oxford-united
        E2|peterboro=peterborough-united
        E2|plymouth=plymouth-argyle
        E2|sheffield-weds=sheffield-wednesday
        E2|stockport=stockport-county
        E2|wigan=wigan-athletic
        E2|wycombe=wycombe-wanderers
        F1|angers=angers-sco
        F1|auxerre=aj-auxerre
        F1|brest=stade-brestois-29
        F1|le-havre=havre-athletic-club
        F1|le-mans=le-mans-fc
        F1|lens=rc-lens
        F1|lille=losc-lille
        F1|lorient=fc-lorient
        F1|lyon=olympique-lyonnais
        F1|marseille=olympique-de-marseille
        F1|monaco=as-monaco
        F1|nice=ogc-nice
        F1|paris-sg=paris-saint-germain
        F1|rennes=stade-rennais-fc
        F1|strasbourg=rc-strasbourg-alsace
        F1|toulouse=toulouse-fc
        F1|troyes=estac-troyes
        F2|annecy=fc-annecy
        F2|boulogne=us-boulogne-co
        F2|clermont=clermont-foot-63
        F2|dijon=dijon-fco
        F2|dunkerque=usl-dunkerque
        F2|grenoble=grenoble-foot-38
        F2|guingamp=en-avant-guingamp
        F2|laval=stade-lavallois-mfc
        F2|metz=fc-metz
        F2|montpellier=montpellier-h-rault-sc
        F2|nancy=as-nancy-lorraine
        F2|nantes=fc-nantes
        F2|red-star=red-star-fc
        F2|reims=stade-de-reims
        F2|rodez=rodez-aveyron-football
        F2|sochaux=fc-sochaux-montb-liard
        F2|st-etienne=as-saint-tienne
        G1|aek=aek-athens
        G1|asteras-tripolis=asteras-tripoli
        G1|levadeiakos=levadiakos
        G1|olympiakos=olympiacos
        I1|inter=internazionale
        I2|avellino=us-avellino
        I2|verona=hellas-verona
        N1|az-alkmaar=az
        N1|cambuur=sc-cambuur
        N1|den-haag=ado-den-haag
        N1|excelsior=excelsior-rotterdam
        N1|for-sittard=fortuna-sittard
        N1|groningen=fc-groningen
        N1|heerenveen=sc-heerenveen
        N1|nijmegen=n-e-c-nijmegen
        N1|psv-eindhoven=psv
        N1|twente=fc-twente
        N1|utrecht=fc-utrecht
        N1|zwolle=pec-zwolle
        P1|academico-viseu=acad-mico
        P1|alverca=fc-alverca
        P1|arouca=fc-arouca
        P1|benfica=sl-benfica
        P1|casa-pia=casa-pia-ac
        P1|estoril=estoril-praia
        P1|estrela=estrela-amadora
        P1|famalicao=fc-famalic-o
        P1|gil-vicente=gil-vicente-fc
        P1|guimaraes=vit-ria-sc
        P1|maritimo=mar-timo-m
        P1|moreirense=moreirense-fc
        P1|nacional=cd-nacional
        P1|porto=fc-porto
        P1|rio-ave=rio-ave-fc
        P1|sp-braga=sc-braga
        P1|sp-lisbon=sporting-cp
        SC0|hearts=heart-of-midlothian
        SP1|alaves=deportivo-alav-s
        SP1|ath-bilbao=athletic-club
        SP1|ath-madrid=atl-tico-de-madrid
        SP1|barcelona=fc-barcelona
        SP1|betis=real-betis
        SP1|elche=elche-cf
        SP1|espanol=rcd-espanyol-de-barcelona
        SP1|getafe=getafe-cf
        SP1|la-coruna=rc-deportivo
        SP1|levante=levante-ud
        SP1|malaga=m-laga-cf
        SP1|osasuna=ca-osasuna
        SP1|santander=r-racing-club
        SP1|sevilla=sevilla-fc
        SP1|sociedad=real-sociedad
        SP1|valencia=valencia-cf
        SP1|vallecano=rayo-vallecano
        SP1|villarreal=villarreal-cf
        SP2|almeria=almer-a
        SP2|andorra=fc-andorra
        SP2|cadiz=c-diz
        SP2|castellon=castell-n
        SP2|celta-b=rc-celta-fortuna
        SP2|cordoba=c-rdoba
        SP2|leganes=legan-s
        SP2|oviedo=real-oviedo
        SP2|sabadell=cd-sabadell
        SP2|sociedad-b=real-sociedad-ii
        SP2|sp-gijon=sporting-gij-n
        SP2|valladolid=real-valladolid
        T1|buyuksehyr=istanbul-basaksehir
        T1|corum=orum
        T1|eyupspor=ey-pspor
        T1|fenerbahce=fenerbah-e
        T1|genclerbirligi=gen-lerbirligi
        T1|goztep=g-ztepe
        T1|rizespor=aykur-rizespor
    """

    /** league -> (archived key -> live key). Built once, on first use. */
    private val byLeague: Map<String, Map<String, String>> by lazy { parse() }

    /** live key -> every archived key that belongs to it, for widening a lookup. */
    private val reverse: Map<String, Set<String>> by lazy {
        val out = HashMap<String, MutableSet<String>>()
        for (bucket in byLeague.values) {
            for ((archived, live) in bucket) {
                out.getOrPut(live) { LinkedHashSet() }.add(archived)
            }
        }
        out
    }

    /** The live id for an archived club key, or null when the archive name is unknown. */
    fun canonical(league: String, archivedKey: String): String? =
        byLeague[league]?.get(archivedKey)

    /**
     * Every archived key that is known to belong to [canonicalKey].
     *
     * Used to look a club's past results up in an archive that stored them under a
     * different name. The set is empty when the club is not in the table, which keeps
     * the caller's behaviour unchanged.
     */
    fun archivedKeys(canonicalKey: String): Set<String> = reverse[canonicalKey].orEmpty()

    /** Number of pairs, for diagnostics and tests. */
    val pairCount: Int get() = byLeague.values.sumOf { it.size }

    /** Read-only view of the table, for diagnostics and tests. Never mutated after parse. */
    internal fun snapshot(): Map<String, Map<String, String>> = byLeague

    private fun parse(): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, MutableMap<String, String>>()
        for (raw in RAW.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val bar = line.indexOf('|')
            val equals = line.indexOf('=', bar + 1)
            if (bar <= 0 || equals <= bar) continue
            val league = line.substring(0, bar)
            val archived = line.substring(bar + 1, equals)
            val live = line.substring(equals + 1)
            if (league.isEmpty() || archived.isEmpty() || live.isEmpty() || archived == live) continue
            val bucket = out.getOrPut(league) { LinkedHashMap() }
            // A club renamed twice maps two archived names onto one live id, which is
            // intended. One archived name pointing at two clubs is not, and the first
            // evidence-backed pair wins.
            if (!bucket.containsKey(archived)) bucket[archived] = live
        }
        return out
    }
}

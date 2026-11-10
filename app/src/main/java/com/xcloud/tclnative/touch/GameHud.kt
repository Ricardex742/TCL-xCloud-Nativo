package com.xcloud.tclnative.touch

import com.xcloud.tclnative.webrtc.TouchKind
import java.util.Locale

enum class HudPreset(val label: String) {
    GAMEPAD("Controle Xbox"),
    CALL_OF_DUTY("Call of Duty"), SHOOTER("Tiro — padrão"), FORZA("Forza Horizon / Motorsport"),
    RACING("Corrida — padrão"), EA_FC("EA SPORTS FC / FIFA"), FOOTBALL("Futebol — padrão"),
    HADES("Hades / Hades II"), ACTION("Ação — padrão"),
    EA_FC_DEFENSE("EA SPORTS FC — defesa"), FOOTBALL_DEFENSE("Futebol — defesa");
    val family: HudPreset get()=when(this) {
        EA_FC_DEFENSE -> EA_FC; FOOTBALL_DEFENSE -> FOOTBALL; else -> this
    }
    val football: Boolean get()=family==EA_FC || family==FOOTBALL
    val shooter: Boolean get()=family in setOf(CALL_OF_DUTY,SHOOTER)
}
enum class HudBinding(val label: String,val button: Int=0) {
    A("A",16),B("B",32),X("X",64),Y("Y",128),LB("LB",4096),RB("RB",8192),
    LT("LT"),RT("RT"),L3("L3",16384),R3("R3",32768),MENU("Menu",4),VIEW("View",8),
    UP("Direcional ↑",256),DOWN("Direcional ↓",512),LEFT("Direcional ←",1024),RIGHT("Direcional →",2048),
    STEER_LEFT("Virar ←"),STEER_RIGHT("Virar →")
}
enum class HudIcon { TEXT,FIRE,AIM,JUMP,CROUCH,RELOAD,SPRINT,USE,WEAPON,BUILD,WALL,STAIRS,FLOOR,ROOF,
    GRENADE,MELEE,ACCELERATE,BRAKE,LEFT,RIGHT,HANDBRAKE,REWIND,CAMERA,GEAR_UP,GEAR_DOWN,
    PASS,SHOOT,THROUGH,CROSS,SWITCH,SHIELD,DASH,ATTACK,SPECIAL,CAST,PAUSE,MAP }
enum class HudShape { CIRCLE,PEDAL,SQUARE }
enum class HudSwipe(val label: String) { UP("↑ Cima"),DOWN("↓ Baixo"),LEFT("← Esquerda"),RIGHT("→ Direita") }
enum class FootballScheme(val label: String) { CLASSIC("Clássico — chute B"),ALTERNATE("Alternativo — chute X") }
data class HudAction(val keys: Set<HudBinding>) {
    constructor(vararg keys: HudBinding): this(keys.toSet())
    val buttons: Int get()=keys.fold(0) { result,key -> result or key.button }
    val label: String get()=HudBinding.entries.filter { it in keys }.joinToString(" + ") { it.label }
}
data class HudGesture(val label: String,val action: HudAction)

data class HudPlacement(val x: Float,val y: Float,val scale: Float=1f,val binding: HudBinding?=null,
                        val action: HudAction?=null,val gestures: Map<HudSwipe,HudGesture>?=null)
data class HudSettings(val preset: HudPreset?=null,val opacity: Int=62,val scale: Int=100,
                       val sensitivity: Int=100,val placements: Map<String,HudPlacement> = emptyMap(),
                       val lookDeadzone: Int=12,val footballScheme: FootballScheme=FootballScheme.CLASSIC,
                       val aimAndFire: Boolean=false,val sprintGesture: Boolean=true) {
    /** Applying a scheme restores its commands without moving the user's buttons. */
    fun withFootballScheme(scheme: FootballScheme)=copy(footballScheme=scheme,
        placements=placements.mapValues { (id,p) ->
            if(id in FOOTBALL_ACTIONS) p.copy(binding=null,action=null,gestures=null) else p
        })
    fun withAimAndFire(enabled: Boolean)=copy(aimAndFire=enabled,
        placements=placements.mapValues { (id,p) ->
            if(id=="fire" || id=="fire-left") p.copy(binding=null,action=null,gestures=null) else p
        })
    companion object {
        private val FOOTBALL_ACTIONS=setOf("pass","shoot","through","cross","sprint","protect",
            "defend-contain","defend-tackle","defend-slide","defend-switch","defend-jockey","defend-press","defend-keeper")
    }
}
data class HudElement(val id: String,val label: String,val x: Float,val y: Float,val radius: Float,
                      val kind: TouchKind=TouchKind.BUTTON,val binding: HudBinding?=null,
                      val icon: HudIcon=HudIcon.TEXT,val shape: HudShape=HudShape.CIRCLE,
                      val dragLook: Boolean=false,val page: HudPreset?=null,
                      val action: HudAction?=binding?.let { HudAction(it) },
                      val gestures: Map<HudSwipe,HudGesture> = emptyMap())

object GameHud {
    private fun key(value: String)=value.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"),"")
    fun automatic(titleId: String,name: String): HudPreset {
        val names=listOf(key(titleId),key(name))
        fun contains(vararg terms: String)=names.any { n -> terms.any { n.contains(it) } }
        return when {
            contains("CALLOFDUTY","CODWARZONE","CODBLACKOPS","WARZONE") -> HudPreset.CALL_OF_DUTY
            contains("FORZAHORIZON","FORZAMOTORSPORT") -> HudPreset.FORZA
            names.any { it=="HADES" || it.startsWith("HADES2") || it.startsWith("HADESII") } -> HudPreset.HADES
            contains("EASPORTSFC","EASPORTSFIFA","FIFA") -> HudPreset.EA_FC
            contains("EFOOTBALL","PROEVOLUTIONSOCCER") -> HudPreset.FOOTBALL
            contains("NEEDFORSPEED","ASSETTOCORSA","THECREWMOTORFEST","DIRTRALLY","WRC") ||
                names.any { it.matches(Regex("F1(?:20)?[0-9]{2}.*")) } -> HudPreset.RACING
            contains("HALOINFINITE","HALOMASTERCHIEF","BATTLEFIELD","OVERWATCH","APEXLEGENDS","RAINBOWSIX","DOOMETERNAL") -> HudPreset.SHOOTER
            else -> HudPreset.GAMEPAD
        }
    }
    fun elements(preset: HudPreset,settings: HudSettings=HudSettings()): List<HudElement> = buildList {
        fun button(id: String,label: String,x: Float,y: Float,binding: HudBinding,icon: HudIcon=HudIcon.TEXT,
                   radius: Float=.052f,shape: HudShape=HudShape.CIRCLE,dragLook: Boolean=false,page: HudPreset?=null,
                   action: HudAction=HudAction(binding),gestures: Map<HudSwipe,HudGesture> = emptyMap()) {
            add(HudElement(id,label,x,y,radius,kind=when(binding) { HudBinding.LT -> TouchKind.LEFT_TRIGGER; HudBinding.RT -> TouchKind.RIGHT_TRIGGER; else -> TouchKind.BUTTON },binding=binding,icon=icon,shape=shape,dragLook=dragLook,page=page,action=action,gestures=gestures))
        }
        fun stick(id: String,label: String,x: Float,y: Float,kind: TouchKind,radius: Float=.115f) {
            add(HudElement(id,label,x,y,radius,kind=kind))
        }
        fun camera() { add(HudElement("look","Arraste para olhar",.68f,.55f,.34f,TouchKind.CAMERA)) }
        fun menus() {
            button("map","Mapa",.43f,.15f,HudBinding.VIEW,HudIcon.MAP,.033f)
            button("pause","Pausa",.57f,.15f,HudBinding.MENU,HudIcon.PAUSE,.033f)
        }
        when(preset) {
            HudPreset.GAMEPAD -> {
                button("a","A",.88f,.81f,HudBinding.A,radius=.046f)
                button("b","B",.935f,.70f,HudBinding.B,radius=.046f)
                button("x","X",.825f,.70f,HudBinding.X,radius=.046f)
                button("y","Y",.88f,.59f,HudBinding.Y,radius=.046f)
                button("lb","LB",.10f,.15f,HudBinding.LB,radius=.046f)
                button("rb","RB",.90f,.15f,HudBinding.RB,radius=.046f)
                button("lt","LT",.10f,.29f,HudBinding.LT,radius=.046f)
                button("rt","RT",.90f,.29f,HudBinding.RT,radius=.046f)
                button("l3","L3",.30f,.49f,HudBinding.L3,radius=.046f)
                button("r3","R3",.72f,.49f,HudBinding.R3,radius=.046f)
                button("pause","☰",.55f,.15f,HudBinding.MENU,radius=.046f)
                button("map","⧉",.45f,.15f,HudBinding.VIEW,radius=.046f)
                stick("move","L",.18f,.73f,TouchKind.LEFT_STICK,.12f)
                stick("right","R",.68f,.73f,TouchKind.RIGHT_STICK,.12f)
                stick("dpad","+",.35f,.75f,TouchKind.DPAD,.080f)
            }
            HudPreset.CALL_OF_DUTY,HudPreset.SHOOTER -> {
                stick("move","Mover",.15f,.76f,TouchKind.LEFT_STICK); camera(); menus()
                val fireAction=if(preset==HudPreset.CALL_OF_DUTY && settings.aimAndFire) HudAction(HudBinding.LT,HudBinding.RT) else HudAction(HudBinding.RT)
                button("fire-left","Atirar",.13f,.27f,HudBinding.RT,HudIcon.FIRE,.065f,dragLook=true,action=fireAction)
                button("fire","Atirar",.91f,.49f,HudBinding.RT,HudIcon.FIRE,.083f,dragLook=true,action=fireAction)
                button("aim","Mirar",.79f,.32f,HudBinding.LT,HudIcon.AIM,.055f,dragLook=true)
                button("jump","Pular",.92f,.75f,HudBinding.A,HudIcon.JUMP,.058f)
                button("crouch","Agachar / deslizar",.81f,.86f,HudBinding.B,HudIcon.CROUCH,.051f)
                button("reload","Usar / recarregar",.72f,.68f,HudBinding.X,HudIcon.RELOAD,.050f)
                button("weapon","Trocar arma",.62f,.86f,HudBinding.Y,HudIcon.WEAPON,.046f)
                button("sprint","Correr",.15f,.43f,HudBinding.L3,HudIcon.SPRINT,.046f)
                button("melee","Corpo a corpo",.62f,.58f,HudBinding.R3,HudIcon.MELEE,.043f)
                button("tactical","Tática",.68f,.25f,HudBinding.LB,HudIcon.GRENADE,.040f)
                button("grenade","Granada",.88f,.22f,HudBinding.RB,HudIcon.GRENADE,.040f)
            }
            HudPreset.FORZA,HudPreset.RACING -> {
                menus()
                stick("dpad","Menus",.27f,.40f,TouchKind.DPAD,.065f)
                button("steer-left","Virar",.095f,.77f,HudBinding.STEER_LEFT,HudIcon.LEFT,.092f)
                button("steer-right","Virar",.25f,.77f,HudBinding.STEER_RIGHT,HudIcon.RIGHT,.092f)
                button("accelerate","Acelerar",.915f,.70f,HudBinding.RT,HudIcon.ACCELERATE,.105f,HudShape.PEDAL)
                button("brake","Frear",.77f,.78f,HudBinding.LT,HudIcon.BRAKE,.080f,HudShape.PEDAL)
                button("handbrake","Freio de mão",.64f,.76f,HudBinding.A,HudIcon.HANDBRAKE,.055f)
                button("rewind","Rebobinar",.11f,.37f,HudBinding.Y,HudIcon.REWIND,.048f)
                button("gear-up","Marcha +",.88f,.34f,HudBinding.B,HudIcon.GEAR_UP,.049f)
                button("gear-down","Marcha −",.76f,.38f,HudBinding.X,HudIcon.GEAR_DOWN,.049f)
                button("camera","Câmera",.64f,.27f,HudBinding.RB,HudIcon.CAMERA,.043f)
                button("clutch","Embreagem",.40f,.80f,HudBinding.LB,HudIcon.HANDBRAKE,.044f)
            }
            HudPreset.EA_FC,HudPreset.FOOTBALL,HudPreset.EA_FC_DEFENSE,HudPreset.FOOTBALL_DEFENSE -> {
                stick("move","Mover",.15f,.74f,TouchKind.LEFT_STICK); menus()
                val defense=preset!=preset.family
                val shot=if(settings.footballScheme==FootballScheme.CLASSIC) HudBinding.B else HudBinding.X
                val cross=if(shot==HudBinding.B) HudBinding.X else HudBinding.B
                val target=if(defense) preset.family else if(preset==HudPreset.EA_FC) HudPreset.EA_FC_DEFENSE else HudPreset.FOOTBALL_DEFENSE
                add(HudElement("football-mode",if(defense) "DEFESA" else "ATAQUE",.855f,.28f,.055f,
                    icon=HudIcon.SWITCH,shape=HudShape.SQUARE,page=target))
                stick("right",if(defense) "Trocar" else "Fintas",.49f,.78f,TouchKind.RIGHT_STICK,.068f)
                if(!defense) {
                    button("pass","Passe",.76f,.80f,HudBinding.A,HudIcon.PASS,.073f,
                        gestures=mapOf(HudSwipe.UP to HudGesture("Tabela",HudAction(HudBinding.LB,HudBinding.A)),
                            HudSwipe.DOWN to HudGesture("Passe forte",HudAction(HudBinding.RB,HudBinding.A))))
                    button("shoot","Chute",.915f,.54f,shot,HudIcon.SHOOT,.087f,gestures=mapOf(
                        HudSwipe.DOWN to HudGesture("Colocado",HudAction(HudBinding.RB,shot)),
                        HudSwipe.UP to HudGesture("Cavadinha",HudAction(HudBinding.LB,shot)),
                        HudSwipe.LEFT to HudGesture("Superchute",HudAction(HudBinding.LB,HudBinding.RB,shot)),
                        HudSwipe.RIGHT to HudGesture("Superchute",HudAction(HudBinding.LB,HudBinding.RB,shot))))
                    button("through","Enfiada",.755f,.55f,HudBinding.Y,HudIcon.THROUGH,.069f,
                        gestures=mapOf(HudSwipe.UP to HudGesture("Lançamento",HudAction(HudBinding.LB,HudBinding.Y))))
                    button("cross","Cruzar",.64f,.70f,cross,HudIcon.CROSS,.050f)
                    button("sprint","Correr",.915f,.83f,HudBinding.RT,HudIcon.SPRINT,.079f)
                    button("protect","Proteger",.11f,.43f,HudBinding.LT,HudIcon.SHIELD,.047f)
                } else {
                    button("defend-contain","Marcar",.915f,.83f,HudBinding.A,HudIcon.SHIELD,.079f,
                        action=HudAction(HudBinding.RT,HudBinding.A))
                    button("defend-tackle","Bote",.915f,.54f,shot,HudIcon.SHOOT,.087f)
                    button("defend-slide","Carrinho",.755f,.55f,cross,HudIcon.CROUCH,.069f)
                    button("defend-switch","Trocar",.76f,.80f,HudBinding.LB,HudIcon.SWITCH,.073f)
                    button("defend-jockey","Conter",.64f,.70f,HudBinding.LT,HudIcon.SHIELD,.050f,
                        action=HudAction(HudBinding.LT,HudBinding.RT))
                    button("defend-press","Pressão",.11f,.43f,HudBinding.RB,HudIcon.SPRINT,.047f)
                    button("defend-keeper","Goleiro",.63f,.39f,HudBinding.Y,HudIcon.USE,.042f)
                }
            }
            HudPreset.HADES,HudPreset.ACTION -> {
                stick("move","Mover",.15f,.75f,TouchKind.LEFT_STICK); menus()
                if(preset==HudPreset.ACTION) stick("right","Câmera",.49f,.76f,TouchKind.RIGHT_STICK,.085f)
                button("dash","Esquiva",.88f,.82f,HudBinding.A,HudIcon.DASH,.066f)
                button("attack","Ataque",.88f,.55f,HudBinding.X,HudIcon.ATTACK,.080f)
                button("special","Especial",.75f,.38f,HudBinding.Y,HudIcon.SPECIAL,.058f)
                button("cast","Feitiço",.72f,.70f,HudBinding.B,HudIcon.CAST,.061f)
                button("interact","Interagir",.60f,.84f,HudBinding.RB,HudIcon.USE,.045f)
                button("assist","Auxílio",.11f,.38f,HudBinding.LB,HudIcon.SHIELD,.045f)
                button("ability","Habilidade",.89f,.24f,HudBinding.RT,HudIcon.SPECIAL,.042f)
                button("extra","Extra",.29f,.34f,HudBinding.LT,HudIcon.CAST,.040f)
            }
        }
    }
}

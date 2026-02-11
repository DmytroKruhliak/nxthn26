package com.nxthn.AIChatbot.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.nxthn.AIChatbot.model.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;


@RestController
public class AIbotController {

    @GetMapping("/healthz")
    public ResponseStatus healthCheck() {
        return new ResponseStatus("OK");
    }

    @PostMapping("/negotiate")
    public List<NegotiationResponse> negotiate(@RequestBody NegotiationRequest request) {
        System.out.println("[KW-BOT] Mega ogudor");

        ThreatAnalyzer threatAnalyzer = new ThreatAnalyzer();
        Map<Integer, ThreatAnalyzer.ThreatScore> threats = threatAnalyzer.analyzeThreats(request);

        // 1. Ищем только тех, кто ЖИВ и КТО НАС АТАКОВАЛ (Месть)
        Integer targetId = threats.values().stream()
                .filter(t -> t.isAggressor && t.totalDamageReceived > 0)
                .max(Comparator.comparingInt(t -> t.totalDamageReceived))
                .map(t -> t.playerId)
                .filter(id -> request.enemyTowers.stream().anyMatch(e -> e.playerId == id && e.hp > 0))
                .orElse(null);

        // 2. Если нас никто не бьет — мы мирные, никого не подговариваем атаковать
        if (targetId == null) {
            // Опционально: можно предложить мир самому сильному, просто чтобы "задобрить"
            Integer strongestId = request.enemyTowers.stream()
                    .filter(e -> e.hp > 0)
                    .max(Comparator.comparingInt(e -> e.level))
                    .map(e -> e.playerId).orElse(null);

            if (strongestId != null) {
                return List.of(new NegotiationResponse(strongestId, null));
            }
            return Collections.emptyList();
        }

        // 3. Если есть агрессор, ищем союзника среди остальных живых
        final Integer finalTargetId = targetId;
        Integer allyId = request.enemyTowers.stream()
                .filter(e -> e.hp > 0)
                .filter(e -> e.playerId != finalTargetId)
                .filter(e -> !threats.get(e.playerId).isAggressor)
                .findFirst()
                .map(e -> e.playerId).orElse(null);

        // Если все живые — агрессоры, выберем самого слабого в надежде на перемирие
        if (allyId == null) {
            allyId = request.enemyTowers.stream()
                    .filter(e -> e.hp > 0)
                    .filter(e -> e.playerId != finalTargetId)
                    .min(Comparator.comparingInt(e -> e.hp))
                    .map(e -> e.playerId).orElse(null);
        }

        if (allyId != null) {
            return List.of(new NegotiationResponse(allyId, finalTargetId));
        }

        return Collections.emptyList();
    }
    // 4. Combat Phase
    @PostMapping("/combat")
    public List<GameAction> combat(@RequestBody CombatRequest request) {
        System.out.println("[KW-BOT] Mega ogudor");

        ThreatAnalyzer threatAnalyzer = new ThreatAnalyzer();
        Map<Integer, ThreatAnalyzer.ThreatScore> threats = threatAnalyzer.analyzeThreatsForCombat(request);

        List<GameAction> actions = new ArrayList<>();
        int budget = request.playerTower.resources;
        int level = request.playerTower.level;
        int myHp = request.playerTower.hp;

        // Состояние: нас бьют или у нас критически мало HP
        boolean beingAttacked = threats.values().stream().anyMatch(t -> t.isAggressor);
        boolean isCritical = myHp < 35;

        // 1. ЗАЩИТА (Если бьют — броня важнее всего)
        if (budget > 0 && (beingAttacked || isCritical)) {
            int armorLimit = isCritical ? 40 : 20;
            if (request.playerTower.armor < armorLimit) {
                int toBuy = Math.min(budget, armorLimit - request.playerTower.armor);
                actions.add(GameAction.armor(toBuy));
                budget -= toBuy;
            }
        }

        // 2. ЭКОНОМИКА (Качаемся, только если нас НЕ трогают)
        int upgradeCost = (int) (50 * Math.pow(1.75, level - 1));
        if (!beingAttacked && !isCritical && level < 6 && budget >= upgradeCost && request.turn < 28) {
            actions.add(GameAction.upgrade());
            budget -= upgradeCost;
        }

        // 3. ВЫБОР ЦЕЛИ (Месть обидчику)
        Integer targetId = threats.values().stream()
                .filter(t -> t.isAggressor)
                .max(Comparator.comparingInt(t -> t.totalDamageReceived))
                .map(t -> t.playerId)
                .filter(id -> isAlive(id, request.enemyTowers)) // Используем наш метод
                .orElse(null);

        // Если Fatigue (25+) или мы уже вкачались (level 4+) - начинаем бить живых сами
        if (targetId == null && (request.turn >= 25 || level >= 4)) {
            targetId = request.enemyTowers.stream()
                    .filter(e -> e.hp > 0)
                    .min(Comparator.comparingInt(e -> e.hp + e.armor))
                    .map(e -> e.playerId).orElse(null);
        }

        // 4. АТАКА
        if (targetId != null && budget > 0) {
            actions.add(GameAction.attack(targetId, budget));
        }

        return actions;
    }

    private boolean isAlive(int id, List<Tower> towers) {
        if (towers == null) return false;
        return towers.stream()
                .anyMatch(t -> t.playerId == id && t.hp > 0);
    }
}


@JsonIgnoreProperties(ignoreUnknown = true)
class Tower {
    public int playerId;
    public int hp;
    public int armor;
    public int resources;
    public int level;
}

class NegotiationRequest {
    public int gameId;
    public int turn;
    public Tower playerTower;
    public List<Tower> enemyTowers;
    public List<CombatRecord> combatActions;
}

class CombatRecord {
    public int playerId;
    public ActionDetail action;
}

class ActionDetail {
    public int targetId;
    public int troopCount;
}

class NegotiationResponse {
    public int allyId;
    public Integer attackTargetId; // Integer позволяет записывать null, если цели нет

    public NegotiationResponse(int allyId, Integer attackTargetId) {
        this.allyId = allyId;
        this.attackTargetId = attackTargetId;
    }
}

class CombatRequest {
    public int gameId;
    public int turn;
    public Tower playerTower;
    public List<Tower> enemyTowers;
    public List<DiplomacyRecord> diplomacy;
    public List<CombatRecord> previousAttacks;
}

class DiplomacyRecord {
    public int playerId;
    public DiplomacyAction action;
}

class DiplomacyAction {
    public int allyId;
    public Integer attackTargetId;
}

@JsonInclude(JsonInclude.Include.NON_NULL)
class GameAction {
    public String type;
    public Integer amount;
    public Integer targetId;
    public Integer troopCount;

    public static GameAction armor(int amount) {
        GameAction a = new GameAction();
        a.type = "armor";
        a.amount = amount;
        return a;
    }

    public static GameAction attack(int target, int troops) {
        GameAction a = new GameAction();
        a.type = "attack";
        a.targetId = target;
        a.troopCount = troops;
        return a;
    }

    public static GameAction upgrade() {
        GameAction a = new GameAction();
        a.type = "upgrade";
        return a;
    }


}

class PlayerMemory {
    int trustScore = 100;
    List<Integer> attacksOnMe = new ArrayList<>();
    List<Boolean> allyKeptWord = new ArrayList<>();
    int lastAttackOnMe = 0;
    int lastTurnAttackedMe = -1;

    public String getTfTStatus(int currentTurn) {
        if (currentTurn <= 3) return "COOPERATE";
        if (lastTurnAttackedMe == currentTurn - 1) return "RETALIATE";
        return "COOPERATE";
    }
}

// Утилитный класс для расчетов по твоим формулам
class StrategyUtils {
    public static double calculatePower(Tower t) {
        return (t.level * 50) + t.hp + (t.armor * 0.7) + (t.resources * 0.5);
    }

    public static String getThreatLevel(double myPower, double enemyPower) {
        double ratio = enemyPower / myPower;
        if (ratio > 1.4) return "CRITICAL";
        if (ratio > 1.15) return "HIGH";
        if (ratio > 0.85) return "NEUTRAL";
        return "LOW";
    }
}


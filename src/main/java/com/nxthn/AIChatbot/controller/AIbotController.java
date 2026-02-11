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
import java.util.concurrent.ConcurrentHashMap;


@RestController
public class AIbotController {

    @GetMapping("/healthz")
    public ResponseStatus healthCheck() {
        return new ResponseStatus("OK");
    }

    private static final Map<Integer, PlayerMemory> memory = new ConcurrentHashMap<>();
    private static Integer currentAllyId = null;

    @PostMapping("/combat")
    public List<GameAction> combat(@RequestBody CombatRequest request) {
        System.out.println("[KW-BOT] Mega ogudor");
        if (request.turn == 1) memory.clear();

        Tower me = request.playerTower;
        double myPower = StrategyUtils.calculatePower(me);
        int budget = me.resources;
        List<GameAction> actions = new ArrayList<>();

        // --- 1. ПРОГНОЗ УРОНА (INCOMING) ---
        int incoming = 0;
        for (Tower enemy : request.enemyTowers) {
            PlayerMemory m = memory.computeIfAbsent(enemy.playerId, k -> new PlayerMemory());

            // Если он в статусе мстителя по TfT
            if (m.getTfTStatus(request.turn).equals("RETALIATE")) {
                incoming += m.lastAttackOnMe * 1.2;
            } else {
                incoming += m.lastAttackOnMe * 0.8;
            }

            // Проверка дипломатии (кто нацелился на нас)
            for (DiplomacyRecord d : request.diplomacy) {
                if (d.playerId == enemy.playerId && d.action.attackTargetId != null && d.action.attackTargetId == me.playerId) {
                    incoming += enemy.resources * 0.4;
                }
            }
        }

        // Если мы лидер - ждем агрессии
        double maxEnemyPower = request.enemyTowers.stream().mapToDouble(StrategyUtils::calculatePower).max().orElse(0);
        if (myPower > maxEnemyPower * 1.2) incoming += 20;

        // --- 2. UPGRADE (Твои условия) ---
        int cost = (int) (50 * Math.pow(1.75, me.level - 1));
        double avgPower = request.enemyTowers.stream().mapToDouble(StrategyUtils::calculatePower).average().orElse(0);

        boolean upgradeCondition = false;
        if (request.turn <= 3 && me.level == 1) upgradeCondition = true;
        else if (request.turn <= 9 && me.level < 3 && incoming < 30) upgradeCondition = true;
        else if (request.turn <= 12 && me.level < 3 && budget >= cost * 1.2) upgradeCondition = true;
        else if (request.turn <= 18 && me.level < 4 && budget >= cost * 1.3 && myPower >= avgPower * 0.9) upgradeCondition = true;
        else if (request.turn >= 25 && me.level < 4 && budget > cost * 2) upgradeCondition = true;

        if (upgradeCondition && budget >= cost && (me.hp + me.armor >= incoming + 20)) {
            actions.add(GameAction.upgrade());
            budget -= cost;
        }

        // --- 3. ARMOR ---
        if (incoming >= 15) {
            int armorToBuild = 0;
            if (me.hp + me.armor < incoming) {
                armorToBuild = Math.min((incoming - me.armor + 10), (int)(budget * 0.6));
            } else {
                armorToBuild = Math.min((int)(incoming * 0.7) - me.armor, (int)(budget * 0.4));
            }
            if (armorToBuild >= 10 && budget >= armorToBuild) {
                actions.add(GameAction.armor(armorToBuild));
                budget -= armorToBuild;
            }
        }

        // --- 4. ATTACK ---
        int attackBudget = budget - 15; // Твой резерв
        if (attackBudget >= 10) {
            List<TargetPriority> targets = new ArrayList<>();
            for (Tower enemy : request.enemyTowers) {
                if (enemy.hp <= 0) continue;

                PlayerMemory m = memory.getOrDefault(enemy.playerId, new PlayerMemory());
                int priority = 0;
                int troops = 0;

                if (enemy.hp <= 30 && (enemy.hp + enemy.armor + 5) <= attackBudget) {
                    priority = 150; troops = enemy.hp + enemy.armor + 5;
                }
                if (m.getTfTStatus(request.turn).equals("RETALIATE")) {
                    priority += 100; troops = Math.max(troops, (int)(m.lastAttackOnMe * 1.2));
                }

                String threat = StrategyUtils.getThreatLevel(myPower, StrategyUtils.calculatePower(enemy));
                if (threat.equals("CRITICAL")) { priority += 80; troops = Math.max(troops, 25); }
                if (threat.equals("HIGH")) { priority += 50; troops = Math.max(troops, 20); }
                if (m.trustScore < 40) { priority += 40; troops = Math.max(troops, 15); }
                if (request.turn >= 25) { priority += 30; troops *= 1.3; }
                if (currentAllyId != null && enemy.playerId == currentAllyId) priority -= 200;

                if (priority > 0) targets.add(new TargetPriority(enemy.playerId, priority, troops));
            }

            targets.sort(Comparator.comparingInt((TargetPriority t) -> t.priority).reversed());
            for (TargetPriority tp : targets) {
                int toSend = Math.min(tp.troops, attackBudget);
                if (toSend >= 10) {
                    actions.add(GameAction.attack(tp.playerId, toSend));
                    attackBudget -= toSend;
                }
            }
        }

        updateMemoryAfterTurn(request);
        return actions;
    }

    private void updateMemoryAfterTurn(CombatRequest request) {
        for (CombatRecord r : request.previousAttacks) {
            PlayerMemory m = memory.computeIfAbsent(r.playerId, k -> new PlayerMemory());
            if (r.action.targetId == request.playerTower.playerId) {
                m.attacksOnMe.add(r.action.troopCount);
                m.lastAttackOnMe = r.action.troopCount;
                m.lastTurnAttackedMe = request.turn;
                m.trustScore = Math.max(0, m.trustScore - 10);
            }
        }
    }

    // Вспомогательный класс для сортировки целей
    class TargetPriority {
        int playerId, priority, troops;
        TargetPriority(int id, int p, int t) { this.playerId = id; this.priority = p; this.troops = t; }
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


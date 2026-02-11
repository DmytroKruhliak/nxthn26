package com.nxthn.AIChatbot.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;


import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RestController
public class AIbotController {

    // Память бота (PlayerId -> Memory)
    private final Map<Integer, PlayerMemory> playerMemories = new ConcurrentHashMap<>();
    private int lastProcessedTurn = -1;

    @GetMapping("/healthz")
    public Map<String, String> healthCheck() {
        return Collections.singletonMap("status", "OK");
    }

    @GetMapping("/info")
    public Map<String, String> info() {
        return Map.of(
                "name", "JavaKingdomStrategy",
                "strategy", "AI-trapped-strategy",
                "version", "1.0"
        );
    }

    @PostMapping("/negotiate")
    public List<NegotiationResponse> negotiate(@RequestBody NegotiationRequest request) {
        System.out.println("[KW-BOT] Mega ogudor");
        updateMemory(request.turn, request.enemyTowers, request.combatActions, null);

        Tower me = request.playerTower;
        double myPower = StrategyUtils.calculatePower(me);

        // 1. Выбор союзника по фазам
        Integer allyId = null;
        if (request.turn <= 10) {
            allyId = findAlly(request.enemyTowers, myPower, "neutral", 70);
        } else if (request.turn <= 20) {
            Tower strongest = StrategyUtils.getStrongest(request.enemyTowers);
            allyId = request.enemyTowers.stream()
                    .filter(e -> e.hp > 0 && e.playerId != strongest.playerId)
                    .filter(e -> !StrategyUtils.getThreatLevel(myPower, StrategyUtils.calculatePower(e)).matches("HIGH|CRITICAL"))
                    .max(Comparator.comparingInt(e -> playerMemories.getOrDefault(e.playerId, new PlayerMemory()).trustScore))
                    .map(e -> e.playerId).orElse(null);
        } else if (request.turn <= 24) {
            allyId = findAlly(request.enemyTowers, myPower, "neutral|high", 0);
        } else {
            allyId = request.enemyTowers.stream()
                    .filter(e -> e.hp > 40)
                    .filter(e -> !StrategyUtils.getThreatLevel(myPower, StrategyUtils.calculatePower(e)).equals("LOW"))
                    .max(Comparator.comparingInt(e -> playerMemories.getOrDefault(e.playerId, new PlayerMemory()).trustScore))
                    .map(e -> e.playerId).orElse(null);
        }

        // 2. Выбор цели (90% топ-1, 10% топ-2 блеф)
        List<Integer> attackPriorities = getAttackTargetsByPriority(request.enemyTowers, me, request.turn);
        Integer targetId = null;
        if (!attackPriorities.isEmpty()) {
            targetId = (Math.random() < 0.9 || attackPriorities.size() < 2)
                    ? attackPriorities.get(0) : attackPriorities.get(1);
        }

        if (allyId != null) {
            return List.of(new NegotiationResponse(allyId, targetId));
        }
        return Collections.emptyList();
    }

    @PostMapping("/combat")
    public List<GameAction> combat(@RequestBody CombatRequest request) {
        System.out.println("[KW-BOT] Mega ogudor");
        updateMemory(request.turn, request.enemyTowers, request.previousAttacks, request.diplomacy);

        List<GameAction> actions = new ArrayList<>();
        Tower me = request.playerTower;
        int budget = me.resources;

        // 1. UPGRADE
        int upgradeCost = StrategyUtils.getUpgradeCost(me.level);
        double avgPower = request.enemyTowers.stream().mapToDouble(StrategyUtils::calculatePower).average().orElse(0);
        int incoming = StrategyUtils.predictDamage(me, request.enemyTowers, playerMemories, request.diplomacy);

        boolean canUpgrade = false;
        if (budget >= upgradeCost && (me.hp + me.armor >= incoming + 20)) {
            if (request.turn <= 3 && me.level == 1) canUpgrade = true;
            else if (request.turn <= 9 && me.level < 3 && incoming < 30) canUpgrade = true;
            else if (request.turn <= 12 && me.level < 3 && budget >= upgradeCost * 1.2) canUpgrade = true;
            else if (request.turn <= 18 && me.level < 4 && budget >= upgradeCost * 1.3 && StrategyUtils.calculatePower(me) >= avgPower * 0.9) canUpgrade = true;
            else if (request.turn >= 25 && me.level < 4 && budget >= upgradeCost * 2) canUpgrade = true;
        }

        if (canUpgrade) {
            actions.add(GameAction.upgrade());
            budget -= upgradeCost;
        }

        // 2. ARMOR
        if (incoming >= 15 && budget >= 10) {
            int armorToBuild = 0;
            if (me.hp + me.armor < incoming) {
                armorToBuild = Math.min(incoming - me.armor + 10, (int)(budget * 0.6));
            } else {
                armorToBuild = Math.min((int)(incoming * 0.7) - me.armor, (int)(budget * 0.4));
            }
            if (armorToBuild >= 10) {
                actions.add(GameAction.armor(armorToBuild));
                budget -= armorToBuild;
            }
        }

        // 3. ATTACK
        int attackBudget = budget - 15; // Резерв 15
        if (attackBudget >= 10) {
            List<AttackCandidate> targets = calculateAttackCandidates(request.enemyTowers, me, request.turn, attackBudget);
            for (AttackCandidate target : targets) {
                int troops = Math.min(target.wantedTroops, attackBudget);
                if (troops >= 10) {
                    actions.add(GameAction.attack(target.id, troops));
                    attackBudget -= troops;
                    playerMemories.computeIfAbsent(target.id, k -> new PlayerMemory()).myAttacksOnThem.add(troops);
                }
            }
        }

        return actions;
    }

    private void updateMemory(int turn, List<Tower> enemies, List<CombatRecord> combat, List<DiplomacyRecord> diplomacy) {
        if (turn <= lastProcessedTurn) return;
        lastProcessedTurn = turn;

        for (Tower e : enemies) {
            playerMemories.putIfAbsent(e.playerId, new PlayerMemory());
        }

        if (combat != null) {
            for (CombatRecord record : combat) {
                PlayerMemory m = playerMemories.get(record.action.targetId == 0 ? record.playerId : record.action.targetId); // Simplified
                if (record.action.targetId != 0 && record.action.targetId == -1) { // -1 placeholder for "me"
                    // Logic to detect if I was attacked
                }
            }
        }
        // В реальном коде здесь детальный парсинг combatActions и diplomacy для обновления trustScore и tft
    }

    private Integer findAlly(List<Tower> enemies, double myPower, String threatPattern, int minTrust) {
        return enemies.stream()
                .filter(e -> e.hp > 0)
                .filter(e -> StrategyUtils.getThreatLevel(myPower, StrategyUtils.calculatePower(e)).matches(threatPattern))
                .filter(e -> playerMemories.getOrDefault(e.playerId, new PlayerMemory()).trustScore >= minTrust)
                .max(Comparator.comparingInt(e -> playerMemories.getOrDefault(e.playerId, new PlayerMemory()).trustScore))
                .map(e -> e.playerId).orElse(null);
    }

    private List<Integer> getAttackTargetsByPriority(List<Tower> enemies, Tower me, int turn) {
        return enemies.stream()
                .filter(e -> e.hp > 0)
                .sorted((a, b) -> Integer.compare(
                        calculatePriority(b, me, turn),
                        calculatePriority(a, me, turn)))
                .map(e -> e.playerId)
                .collect(Collectors.toList());
    }

    private int calculatePriority(Tower enemy, Tower me, int turn) {
        PlayerMemory mem = playerMemories.getOrDefault(enemy.playerId, new PlayerMemory());
        int p = 0;
        if (mem.getTfTStatus(turn).equals("RETALIATE")) p += 100;
        if (enemy.hp < 35) p += 60;
        String threat = StrategyUtils.getThreatLevel(StrategyUtils.calculatePower(me), StrategyUtils.calculatePower(enemy));
        if (threat.equals("CRITICAL")) p += 80;
        if (threat.equals("HIGH")) p += 50;
        if (mem.trustScore < 30) p += 40;
        return p;
    }

    private List<AttackCandidate> calculateAttackCandidates(List<Tower> enemies, Tower me, int turn, int budget) {
        List<AttackCandidate> list = new ArrayList<>();
        for (Tower e : enemies) {
            if (e.hp <= 0) continue;
            int p = calculatePriority(e, me, turn);
            int troops = 0;
            if (e.hp <= 30) troops = e.hp + e.armor + 5;
            if (p >= 100) troops = Math.max(troops, 25);
            if (turn >= 25) troops *= 1.3;
            if (p > 0) list.add(new AttackCandidate(e.playerId, p, troops));
        }
        list.sort((a, b) -> Integer.compare(b.priority, a.priority));
        return list;
    }

    @Data static class AttackCandidate {
        final int id; final int priority; final int wantedTroops;
    }
}

class PlayerMemory {
    int trustScore = 100;
    List<Integer> attacksOnMe = new ArrayList<>();
    List<Integer> myAttacksOnThem = new ArrayList<>();

    public String getTfTStatus(int turn) {
        if (turn <= 3) return "COOPERATE";
        // Упрощенная логика: если в списке атак на меня есть записи с прошлого хода
        return "COOPERATE";
    }
}

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

    public static int getUpgradeCost(int level) {
        return (int) (50 * Math.pow(1.75, level - 1));
    }

    public static Tower getStrongest(List<Tower> towers) {
        return towers.stream().max(Comparator.comparingDouble(StrategyUtils::calculatePower)).orElse(null);
    }

    public static int predictDamage(Tower me, List<Tower> enemies, Map<Integer, PlayerMemory> memories, List<DiplomacyRecord> diplomacy) {
        int incoming = 0;
        // Здесь логика прогноза на основе истории атак и дипломатии (кто пообещал нас бить)
        return incoming;
    }
}

// DTO классы остаются как в вашем примере с добавлением необходимых полей

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


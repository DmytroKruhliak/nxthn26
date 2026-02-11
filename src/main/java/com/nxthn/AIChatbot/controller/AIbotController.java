package com.nxthn.AIChatbot.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RestController
public class AIbotController {

    // Глобальная память бота (в рамках сессии сервера)
    private static final Map<Integer, PlayerMemory> playerMemories = new ConcurrentHashMap<>();
    private static int lastProcessedTurn = -1;

    @GetMapping("/healthz")
    public Map<String, String> healthCheck() {
        return Collections.singletonMap("status", "OK");
    }

    @GetMapping("/info")
    public Map<String, String> info() {
        return Map.of(
                "name", "KingdomMaster_v1",
                "strategy", "AI-trapped-strategy",
                "version", "1.1"
        );
    }

    @PostMapping("/negotiate")
    public List<NegotiationResponse> negotiate(@RequestBody NegotiationRequest request) {
        System.out.println("[KW-BOT] Mega ogudor");

        // Синхронизируем память на основе данных о боях прошлого хода
        syncMemory(request.turn, request.enemyTowers, request.combatActions, null);

        Tower me = request.playerTower;
        double myPower = StrategyUtils.calculatePower(me);

        // 1. Выбор союзника (логика по фазам ходов)
        Integer allyId = null;
        if (request.turn <= 10) {
            allyId = findAlly(request.enemyTowers, myPower, "NEUTRAL", 70);
        } else if (request.turn <= 20) {
            Tower strongest = StrategyUtils.getStrongest(request.enemyTowers);
            allyId = request.enemyTowers.stream()
                    .filter(e -> e.hp > 0 && e.playerId != strongest.playerId)
                    .filter(e -> !StrategyUtils.getThreatLevel(myPower, StrategyUtils.calculatePower(e)).matches("HIGH|CRITICAL"))
                    .max(Comparator.comparingInt(e -> getMem(e.playerId).trustScore))
                    .map(e -> e.playerId).orElse(null);
        } else {
            allyId = findAlly(request.enemyTowers, myPower, "NEUTRAL|HIGH", 30);
        }

        // 2. Выбор цели для дипломатии (90% - приоритет 1, 10% - блеф)
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

        // Обновляем память на основе атак, которые произошли ТОЛЬКО ЧТО (в этот ход)
        syncMemory(request.turn, request.enemyTowers, request.previousAttacks, request.diplomacy);

        List<GameAction> actions = new ArrayList<>();
        Tower me = request.playerTower;
        int budget = me.resources;

        // 1. Прогноз входящего урона
        int incoming = StrategyUtils.predictDamage(me, request.enemyTowers, playerMemories, request.diplomacy, request.turn);

        // 2. UPGRADE Logic
        int upgradeCost = StrategyUtils.getUpgradeCost(me.level);
        double avgPower = request.enemyTowers.stream().mapToDouble(StrategyUtils::calculatePower).average().orElse(0);

        boolean canUpgrade = false;
        if (me.level < 6 && budget >= upgradeCost && (me.hp + me.armor >= incoming + 20)) {
            if (request.turn <= 3 && me.level == 1) canUpgrade = true;
            else if (request.turn <= 9 && me.level < 3 && incoming < 30) canUpgrade = true;
            else if (request.turn <= 18 && me.level < 4 && budget >= upgradeCost * 1.3 && StrategyUtils.calculatePower(me) >= avgPower * 0.9) canUpgrade = true;
            else if (request.turn >= 25 && me.level < 4 && budget >= upgradeCost * 2.0) canUpgrade = true;
        }

        if (canUpgrade) {
            actions.add(GameAction.upgrade());
            budget -= upgradeCost;
        }

        // 3. ARMOR Logic
        if (incoming >= 15 && budget >= 10) {
            int armorToBuild;
            if (me.hp + me.armor < incoming) {
                armorToBuild = Math.min(incoming - me.armor + 10, (int) (budget * 0.6));
            } else {
                armorToBuild = Math.min((int) (incoming * 0.7) - me.armor, (int) (budget * 0.4));
            }
            if (armorToBuild >= 10) {
                actions.add(GameAction.armor(armorToBuild));
                budget -= armorToBuild;
            }
        }

        // 4. ATTACK Logic
        int attackBudget = budget - 15; // Резерв
        if (attackBudget >= 10) {
            List<AttackCandidate> targets = calculateAttackCandidates(request.enemyTowers, me, request.turn);
            for (AttackCandidate target : targets) {
                int troops = Math.min(target.wantedTroops, attackBudget);
                if (troops >= 10) {
                    actions.add(GameAction.attack(target.id, troops));
                    attackBudget -= troops;
                    getMem(target.id).myAttacksOnThem.add(troops);
                }
            }
        }

        return actions;
    }

    // --- Вспомогательные методы управления памятью ---

    private void syncMemory(int turn, List<Tower> enemies, List<CombatRecord> combat, List<DiplomacyRecord> diplomacy) {
        // Инициализируем новых игроков
        for (Tower e : enemies) {
            playerMemories.putIfAbsent(e.playerId, new PlayerMemory());
        }

        if (combat != null) {
            for (CombatRecord record : combat) {
                // Если кто-то атаковал НАС (в правилах targetId должен совпадать с нашим)
                // Но так как в JSON targetId может быть равен ID нашей башни:
                int attackerId = record.playerId;
                PlayerMemory mem = getMem(attackerId);

                // Здесь логика: если в списке атак есть запись, где цель - это МЫ
                // Мы не знаем свой ID из некоторых контекстов, поэтому проверяем по косвенным признакам
                // В идеале в запросе должен быть наш playerId. Допустим, мы его знаем.
                mem.lastAttackDamage = record.action.troopCount;
                mem.lastTurnAttackedMe = turn;
                mem.attacksOnMe.add(record.action.troopCount);
                mem.trustScore = Math.max(0, mem.trustScore - 10);
            }
        }

        if (diplomacy != null) {
            for (DiplomacyRecord dip : diplomacy) {
                PlayerMemory mem = getMem(dip.playerId);
                if (dip.action.attackTargetId != null && dip.action.attackTargetId != 0) {
                    // Если он обещал НЕ бить нас (allyId == наш) - это плюс к доверию потом
                    // Если он обещал бить кого-то и сделал это - trust += 5
                }
            }
        }
        lastProcessedTurn = turn;
    }

    private PlayerMemory getMem(int id) {
        return playerMemories.getOrDefault(id, new PlayerMemory());
    }

    private Integer findAlly(List<Tower> enemies, double myPower, String threatPattern, int minTrust) {
        return enemies.stream()
                .filter(e -> e.hp > 0)
                .filter(e -> StrategyUtils.getThreatLevel(myPower, StrategyUtils.calculatePower(e)).matches(threatPattern))
                .filter(e -> getMem(e.playerId).trustScore >= minTrust)
                .max(Comparator.comparingInt(e -> getMem(e.playerId).trustScore))
                .map(e -> e.playerId).orElse(null);
    }

    private List<Integer> getAttackTargetsByPriority(List<Tower> enemies, Tower me, int turn) {
        return enemies.stream()
                .filter(e -> e.hp > 0)
                .sorted((a, b) -> Integer.compare(calculatePriority(b, me, turn), calculatePriority(a, me, turn)))
                .map(e -> e.playerId)
                .collect(Collectors.toList());
    }

    private int calculatePriority(Tower enemy, Tower me, int turn) {
        PlayerMemory mem = getMem(enemy.playerId);
        int p = 0;
        if (mem.getTfTStatus(turn).equals("RETALIATE")) p += 100;
        if (enemy.hp < 35) p += 60;
        String threat = StrategyUtils.getThreatLevel(StrategyUtils.calculatePower(me), StrategyUtils.calculatePower(enemy));
        if (threat.equals("CRITICAL")) p += 80;
        if (threat.equals("HIGH")) p += 50;
        if (mem.trustScore < 30) p += 40;
        return p;
    }

    private List<AttackCandidate> calculateAttackCandidates(List<Tower> enemies, Tower me, int turn) {
        List<AttackCandidate> list = new ArrayList<>();
        for (Tower e : enemies) {
            if (e.hp <= 0) continue;
            int p = calculatePriority(e, me, turn);
            int troops = 10; // min
            if (e.hp <= 30) troops = e.hp + e.armor + 5;
            else if (p >= 100) troops = 25;
            else if (p >= 50) troops = 20;

            if (turn >= 25) troops = (int)(troops * 1.3);
            if (p > 0) list.add(new AttackCandidate(e.playerId, p, troops));
        }
        list.sort((a, b) -> Integer.compare(b.priority, a.priority));
        return list;
    }

    @Data @AllArgsConstructor static class AttackCandidate { int id; int priority; int wantedTroops; }
}

// --- СТРУКТУРЫ ДАННЫХ ---

class PlayerMemory {
    int trustScore = 100;
    int lastTurnAttackedMe = -1;
    int lastAttackDamage = 0;
    List<Integer> attacksOnMe = new ArrayList<>();
    List<Integer> myAttacksOnThem = new ArrayList<>();
    List<Boolean> allyKeptWord = new ArrayList<>();

    public String getTfTStatus(int currentTurn) {
        if (currentTurn <= 3) return "COOPERATE";
        // Если нас атаковали на прошлом ходу - мстим
        if (lastTurnAttackedMe == currentTurn - 1) return "RETALIATE";
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

    public static int predictDamage(Tower me, List<Tower> enemies, Map<Integer, PlayerMemory> memories, List<DiplomacyRecord> diplomacy, int turn) {
        double incoming = 0;
        for (Tower enemy : enemies) {
            if (enemy.hp <= 0) continue;
            PlayerMemory mem = memories.getOrDefault(enemy.playerId, new PlayerMemory());

            double enemyPotential = 0;
            if (mem.lastTurnAttackedMe == turn - 1) {
                enemyPotential = mem.lastAttackDamage * 1.1;
            }

            // Дипломатические угрозы
            if (diplomacy != null) {
                for (DiplomacyRecord dr : diplomacy) {
                    if (dr.playerId == enemy.playerId && dr.action.attackTargetId != null) {
                        // Если он открыто заявил, что бьет нас
                        // Мы не всегда знаем свой ID в этом контексте, но обычно он в playerTower
                        enemyPotential += (enemy.resources * 0.3);
                    }
                }
            }
            incoming += Math.min(enemyPotential, enemy.resources);
        }

        // Лидерский штраф
        Tower strongest = getStrongest(enemies);
        if (strongest != null && calculatePower(me) > calculatePower(strongest) * 1.2) {
            incoming += 20;
        }

        // Урон от Fatigue
        if (turn >= 25) incoming += (turn - 24) * 2;

        return (int) Math.round(incoming);
    }
}

// --- DTO CLASSES (JSON) ---

@Data @JsonIgnoreProperties(ignoreUnknown = true)
class Tower {
    public int playerId;
    public int hp;
    public int armor;
    public int resources;
    public int level;
}

@Data
class NegotiationRequest {
    public int turn;
    public Tower playerTower;
    public List<Tower> enemyTowers;
    public List<CombatRecord> combatActions;
}

@Data
class CombatRecord {
    public int playerId;
    public ActionDetail action;
}

@Data @JsonIgnoreProperties(ignoreUnknown = true)
class ActionDetail {
    public Integer targetId;
    public int troopCount;
}

@Data @AllArgsConstructor
class NegotiationResponse {
    public int allyId;
    public Integer attackTargetId;
}

@Data
class CombatRequest {
    public int turn;
    public Tower playerTower;
    public List<Tower> enemyTowers;
    public List<DiplomacyRecord> diplomacy;
    public List<CombatRecord> previousAttacks;
}

@Data
class DiplomacyRecord {
    public int playerId;
    public DiplomacyAction action;
}

@Data @JsonIgnoreProperties(ignoreUnknown = true)
class DiplomacyAction {
    public Integer allyId;
    public Integer attackTargetId;
}

@Data @JsonInclude(JsonInclude.Include.NON_NULL)
class GameAction {
    public String type;
    public Integer amount;
    public Integer targetId;
    public Integer troopCount;

    public static GameAction armor(int amount) {
        GameAction a = new GameAction(); a.type = "armor"; a.amount = amount; return a;
    }
    public static GameAction attack(int target, int troops) {
        GameAction a = new GameAction(); a.type = "attack"; a.targetId = target; a.troopCount = troops; return a;
    }
    public static GameAction upgrade() {
        GameAction a = new GameAction(); a.type = "upgrade"; return a;
    }
}

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

        EconomicAnalyzer ecoAnalyzer = new EconomicAnalyzer();
        List<EconomicAnalyzer.EnemyEconomy> ecoLeaderboard = ecoAnalyzer.analyze(request.enemyTowers);

        List<GameAction> actions = new ArrayList<>();
        int budget = request.playerTower.resources;
        int currentLevel = request.playerTower.level;

        // --- ШАГ 1: МАКСИМАЛЬНЫЙ UPGRADE ---
        // Формула стоимости: 50 * (1.75 ^ (level - 1))
        int upgradeCost = (int) (50 * Math.pow(1.75, currentLevel - 1));

        // Качаемся до упора (уровень 5-6), если хватает денег
        if (currentLevel < 6 && budget >= upgradeCost) {
            actions.add(GameAction.upgrade());
            budget -= upgradeCost;
        }

        // --- ШАГ 2: ЗАЩИТА (ARMOR) ---
        // Покупаем броню, если нас атакуют или если бюджет позволяет
        int minArmor = (request.turn > 20) ? 30 : 10;
        if (budget > 0 && request.playerTower.armor < minArmor) {
            int toBuy = Math.min(budget, minArmor - request.playerTower.armor);
            actions.add(GameAction.armor(toBuy));
            budget -= toBuy;
        }

        // --- ШАГ 3: ОТВЕТНАЯ АТАКА (REVENGE) ---
        Integer targetId = TargetSelector.selectBestTarget(request, threats, ecoLeaderboard);

        // Бьем ТОЛЬКО если есть targetId (то есть была агрессия)
        if (targetId != null && budget > 0) {
            actions.add(GameAction.attack(targetId, budget));
        }

        return actions;
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


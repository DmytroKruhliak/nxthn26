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

        // 1. Кто нас обидел? (Шаг 1)
        ThreatAnalyzer threatAnalyzer = new ThreatAnalyzer();
        Map<Integer, ThreatAnalyzer.ThreatScore> threats = threatAnalyzer.analyzeThreats(request);

        // 2. Кто станет монстром? (Шаг 2)
        EconomicAnalyzer ecoAnalyzer = new EconomicAnalyzer();
        List<EconomicAnalyzer.EnemyEconomy> ecoLeaderboard = ecoAnalyzer.analyze(request.enemyTowers);

        // 3. Выбор ГЛАВНОЙ ЦЕЛИ (Target)
        // Приоритет: Если есть наглый агрессор - бьем его. Если нет - бьем самого богатого.
        Integer targetId = null;

        Integer mainAggressorId = threats.values().stream()
                .filter(t -> t.isAggressor && t.totalDamageReceived > 10) // Игнорируем мелкие тычки
                .max(Comparator.comparingInt(t -> t.totalDamageReceived))
                .map(t -> t.playerId).orElse(null);

        if (mainAggressorId != null) {
            targetId = mainAggressorId;
        } else if (!ecoLeaderboard.isEmpty()) {
            targetId = ecoLeaderboard.get(0).playerId; // Бьем самого богатого
        }

        // 4. Выбор СОЮЗНИКА (Ally)
        // Дружим с тем, кто НЕ агрессор и НЕ самый богатый (чтобы не помогать лидеру)
        final Integer finalTargetId = targetId;
        Integer allyId = request.enemyTowers.stream()
                .filter(e -> e.playerId != finalTargetId)
                .filter(e -> !threats.get(e.playerId).isAggressor)
                .min(Comparator.comparingInt(e -> e.level)) // Выбираем самого слабого в помощники
                .map(e -> e.playerId).orElse(null);

        if (allyId != null && targetId != null) {
            return List.of(new NegotiationResponse(allyId, targetId));
        }

        return Collections.emptyList();
    }

    // 4. Combat Phase
    @PostMapping("/combat")
    public List<GameAction> combat(@RequestBody CombatRequest request) {
        System.out.println("[KW-BOT] Mega ogudor");

        List<GameAction> actions = new ArrayList<>();
        int resources = request.playerTower.resources;

        int currentLevel = request.playerTower.level;
        int upgradeCost = (int) (50 * Math.pow(1.75, currentLevel - 1));

        if (resources >= upgradeCost && currentLevel < 5) {
            actions.add(GameAction.upgrade());
            resources -= upgradeCost;
        }

        if (resources > 0 && request.playerTower.armor < 10) {
            int armorToBuy = Math.min(resources, 10 - request.playerTower.armor);
            actions.add(GameAction.armor(armorToBuy));
            resources -= armorToBuy;
        }

        if (resources > 0 && !request.enemyTowers.isEmpty()) {
            int targetId = request.enemyTowers.get(0).playerId;
            actions.add(GameAction.attack(targetId, resources));
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


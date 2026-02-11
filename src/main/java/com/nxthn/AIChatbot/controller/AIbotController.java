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

        // Вызываем наши анализаторы
        ThreatAnalyzer threatAnalyzer = new ThreatAnalyzer();
        Map<Integer, ThreatAnalyzer.ThreatScore> threats = threatAnalyzer.analyzeThreats(request);

        EconomicAnalyzer ecoAnalyzer = new EconomicAnalyzer();
        List<EconomicAnalyzer.EnemyEconomy> ecoLeaderboard = ecoAnalyzer.analyze(request.enemyTowers);

        // 1. ОПРЕДЕЛЯЕМ ЖЕРТВУ (Кого боимся или ненавидим)
        Integer targetId = null;
        Integer mainAggressorId = threats.values().stream()
                .filter(t -> t.isAggressor && t.totalDamageReceived > 10)
                .max(Comparator.comparingInt(t -> t.totalDamageReceived))
                .map(t -> t.playerId).orElse(null);

        if (mainAggressorId != null) {
            targetId = mainAggressorId;
        } else if (!ecoLeaderboard.isEmpty()) {
            targetId = ecoLeaderboard.get(0).playerId;
        }

        if (targetId == null) return Collections.emptyList();

        // 2. ВЫБИРАЕМ СОЮЗНИКА (Кому доверяем)
        final Integer finalTargetId = targetId;
        Integer allyId = request.enemyTowers.stream()
                .filter(e -> e.playerId != finalTargetId) // Не союзничаем с жертвой
                .filter(e -> !threats.get(e.playerId).isAggressor) // Не союзничаем с обидчиком
                .findFirst()
                .map(e -> e.playerId).orElse(null);

        // Если все враги агрессивны, ищем самого слабого из них (кроме цели), чтобы "расколоть" их коалицию
        if (allyId == null) {
            allyId = request.enemyTowers.stream()
                    .filter(e -> e.playerId != finalTargetId)
                    .min(Comparator.comparingInt(e -> e.hp))
                    .map(e -> e.playerId).orElse(null);
        }

        // 3. УПАКОВКА JSON
        if (allyId != null) {
            // Мы отправляем ОДНО сообщение ОДНОМУ союзнику
            return List.of(new NegotiationResponse(allyId, finalTargetId));
        }

        return Collections.emptyList();
    }
    // 4. Combat Phase
    @PostMapping("/combat")
    public List<GameAction> combat(@RequestBody CombatRequest request) {
        System.out.println("[KW-BOT] Mega ogudor");

        // Используем уже созданные нами анализаторы
        ThreatAnalyzer threatAnalyzer = new ThreatAnalyzer();
        // (Для боя нам нужно передать данные о врагах и прошлых атаках)
        Map<Integer, ThreatAnalyzer.ThreatScore> threats = threatAnalyzer.analyzeThreatsForCombat(request);

        EconomicAnalyzer ecoAnalyzer = new EconomicAnalyzer();
        List<EconomicAnalyzer.EnemyEconomy> ecoLeaderboard = ecoAnalyzer.analyze(request.enemyTowers);

        // ВЫБОР ЖЕРТВЫ (Шаг 3)
        Integer targetId = TargetSelector.selectBestTarget(request, threats, ecoLeaderboard);

        // РАСПРЕДЕЛЕНИЕ РЕСУРСОВ
        List<GameAction> actions = new ArrayList<>();
        int budget = request.playerTower.resources;

        // 1. Сначала проверяем UPGRADE (Инвестиция в будущее)
        int currentLevel = request.playerTower.level;
        int upgradeCost = (int) (50 * Math.pow(1.75, currentLevel - 1));

        // Стратегия: апгрейдимся, если HP позволяет (не при смерти)
        if (budget >= upgradeCost && request.playerTower.hp > 30) {
            actions.add(GameAction.upgrade());
            budget -= upgradeCost;
        }

        // 2. Покупаем ARMOR (Минимальная защита)
        // Если нас били на прошлом ходу или скоро Fatigue (25 ход), укрепляемся
        int armorNeeds = (request.turn > 20) ? 20 : 10;
        if (budget > 0 && request.playerTower.armor < armorNeeds) {
            int toBuy = Math.min(budget, armorNeeds - request.playerTower.armor);
            actions.add(GameAction.armor(toBuy));
            budget -= toBuy;
        }

        // 3. Остаток - ATTACK (Реализация Шага 3)
        if (budget > 0 && targetId != null) {
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


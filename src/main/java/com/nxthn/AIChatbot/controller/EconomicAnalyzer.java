package com.nxthn.AIChatbot.controller;

import java.util.Comparator;
import java.util.List;

public class EconomicAnalyzer {

    // Рассчитывает доход на основе уровня
    public static int calculateIncome(int level) {
        return (int) (20 * Math.pow(1.5, level - 1));
    }

    public static class EnemyEconomy {
        public int playerId;
        public int currentLevel;
        public int nextTurnIncome;
        public double threatLevel; // Комбинированный показатель опасности

        public EnemyEconomy(Tower tower) {
            this.playerId = tower.playerId;
            this.currentLevel = tower.level;
            this.nextTurnIncome = calculateIncome(tower.level);
            // Опасность = доход / (HP + armor). Чем выше доход и меньше защиты, тем вкуснее цель
            this.threatLevel = (double) nextTurnIncome;
        }
    }

    public List<EnemyEconomy> analyze(List<Tower> enemies) {
        return enemies.stream()
                .map(EnemyEconomy::new)
                .sorted(Comparator.comparingDouble((EnemyEconomy e) -> e.threatLevel).reversed())
                .toList();
    }
}

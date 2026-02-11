# Алгоритм стратегии Kingdom Wars

## ПАМЯТЬ (обновляем каждый ход)
```
Для каждого игрока:
- attacks_on_me[] - история атак на меня
- my_attacks_on_them[] - мои атаки на него
- trust_score (0-100, старт 100)
- ally_kept_word[] - выполнял ли обещания
```

## МЕТРИКИ

**Сила игрока:**
```
Power = Level*50 + HP + Armor*0.7 + Resources*0.5
```

**Угроза:**
```
ratio = их_сила / моя_сила
> 1.4 = critical
> 1.15 = high
> 0.85 = neutral
< 0.85 = low
```

**Статус Tit-for-Tat:**
```
IF turn <= 3: cooperate
IF последний_ход_атаковал AND не_мстили: retaliate
IF последний_ход_атаковал AND уже_мстили: forgive
ELSE: cooperate
```

## NEGOTIATE PHASE

### 1. Выбор союзника

**Ходы 1-10:**
- Фильтр: угроза = neutral, trust > 70, tft = cooperate
- Выбрать с максимальным score

**Ходы 11-20:**
- Найти strongest_player
- Фильтр: НЕ strongest, НЕ high/critical угроза
- Бонус: tft = cooperate
- Выбрать с максимальным score

**Ходы 21-24:**
- Фильтр: угроза = neutral/high, tft = cooperate
- Выбрать с максимальным score

**Ходы 25+:**
- Фильтр: HP > 40, угроза НЕ low
- Выбрать с максимальным score

### 2. Выбор цели атаки

Приоритеты (складываются):
- tft = retaliate: +100
- HP < 35: +60
- угроза critical: +80
- угроза high: +50
- trust < 30: +40
- союзник: -200

90% времени объявляем цель с максимальным приоритетом
10% времени - вторую по приоритету (блеф)

## COMBAT PHASE

### 1. UPGRADE

**Апгрейдимся если выполнено:**
```
(turn <= 3 AND level = 1) OR
(turn <= 9 AND level < 3 AND incoming_damage < 30) OR
(turn <= 12 AND level < 3 AND resources >= cost*1.2) OR
(turn <= 18 AND level < 4 AND resources >= cost*1.3 AND my_power >= avg_power*0.9) OR
(turn >= 25 AND level < 4 AND resources > cost*2)

AND resources >= cost
AND HP + armor >= incoming_damage + 20
```

### 2. ARMOR

**Прогноз урона:**
```
incoming = 0

Для каждой прошлой атаки на меня:
  IF tft(атакующий) = retaliate: incoming += урон * 1.2
  ELSE: incoming += урон * 0.8

Для каждой дипломатии где attackTargetId = я:
  incoming += ресурсы_атакующего * 0.4

IF я_лидер (моя_сила > макс_чужая * 1.2):
  incoming += 20
```

**Решение:**
```
IF incoming < 15: нет брони

IF HP + current_armor < incoming:
  armor = min(incoming - current_armor + 10, resources * 0.6)
ELSE:
  armor = min((incoming * 0.7) - current_armor, resources * 0.4)

IF armor >= 10: строить armor
```

### 3. ATTACK

**Резерв:** 15 ресурсов
**Бюджет:** resources - потрачено - резерв

**Для каждого врага считаем:**
```
priority = 0
troops = 0

IF HP <= 30 AND (HP + armor + 5) <= budget:
  priority = 150
  troops = HP + armor + 5

IF tft = retaliate:
  priority += 100
  troops = max(troops, last_attack_on_me * 1.2)

IF угроза critical: priority += 80, troops = max(troops, 25)
IF угроза high: priority += 50, troops = max(troops, 20)
IF trust < 40: priority += 40, troops = max(troops, 15)
IF turn >= 25: priority += 30, troops *= 1.3

IF текущий_союзник: priority -= 200

IF priority == 0 AND turn > 10 AND их_сила < моя*0.7:
  priority = 20, troops = 15
```

**Распределение:**
1. Сортировать по priority (убывание)
2. Для каждого где priority > 0 AND budget >= 10:
    - Атаковать min(troops, budget)
    - budget -= войска
    - Записать my_attacks_on_them

## ОБНОВЛЕНИЕ ПАМЯТИ

**После каждого хода:**
```
Для каждой атаки:
  IF target = я:
    attacks_on_me[attacker].append(damage)
    trust[attacker] -= 10

Для каждой дипломатии где allyId = я:
  IF attackTargetId объявлено:
    IF атаковал объявленную цель: kept_word = True, trust += 5
    IF атаковал меня: kept_word = False, trust -= 30
    ally_kept_word[ally].append(kept_word)

trust = clamp(trust, 0, 100)
```

## КОНСТАНТЫ
```
Upgrade costs: 50, 88, 153, 268, 469
Min armor: 10
Min attack: 10
Reserve: 15
Finish threshold: HP <= 30
Threat threshold: incoming >= 15
Leader threshold: power > max_other * 1.2
```
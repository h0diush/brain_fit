# BrainFit — контекст для Claude Code

Офлайн-приложение для Android: шесть коротких игр на память, внимание и скорость. ТЗ — `BrainFit v0.1 — ТЗ.md`
(раздел 13 — правила работы, раздел 10 — этапы). Решения и открытые вопросы — `docs/decisions.md`.

## Где мы

Сделаны этапы 1 (фундамент), 2 (игровой движок: `Game`, `GameSession`, `PlayController`, экран-хост, запись,
детектор тряски), 3 (Шульте, математический спринт, Корси), 4 (Струп, N-back, «Двойное решение»), 5 (калибровка,
адаптация уровней, быстрая и стандартная тренировки, «Продолжить»; Room версии 3) и 6 (прогресс: графики на Vico,
рекорды по уровням, история уровней, серия дней, сравнение на экране результата). Дальше этап 7 — полировка и релиз:
настройки, экспорт и импорт, вибро и звук, подгонка порогов. Работать строго по этапам, после каждого — сборка, тесты, отчёт.

Уровень игры ведёт `PlayController` по правилам `domain/adaptive/Adaptation` и пишет его вместе с итогом сессии
(`SessionRepository.finishSession`). Подбор игр в тренировку — `domain/workout/WorkoutPlanner`.
Прогресс считается на лету из сессий (`domain/progress/Progress`, метрики игр — `ProgressMetrics`), в базе не хранится.

Новая игра = логика в `games/<имя>/` (реализация `Game`, чистый Kotlin, тест с массовым прогоном генератора) +
экран раунда в `feature/play/game/` + модуль с инструкцией в `GameModules.kt` и строка в `GameRegistry.ALL`.
Инструкцию, пробную попытку, паузу, раунды, запись и результат делает хост — в игре их не повторять.
Нажатия в играх — `Modifier.onPressDown`, размер цифр — `fixedSp` (`core/designsystem/Press.kt`).

## Сборка (Windows)

JDK 21 — `C:\Users\HAS\.jdks\jbr-21.0.11` (в PATH его нет, задавать `JAVA_HOME`). SDK — `D:\LOCALAPPDATA\Android\Sdk`.

```
gradlew.bat testDebugUnitTest          # JVM-тесты (app/src/test)
gradlew.bat assembleDebug
gradlew.bat assembleRelease            # app\build\outputs\apk\release\app-release.apk, подписан debug-ключом
gradlew.bat connectedDebugAndroidTest  # Room на устройстве (app/src/androidTest)
```

Из среды Claude Gradle запускать с `--no-daemon "-Pkotlin.compiler.execution.strategy=in-process"`.
Эмулятор без окна: `emulator -avd Medium_Phone_API_37.0 -no-window -no-audio -gpu swiftshader_indirect`
(без `-gpu swiftshader_indirect` окна приложений на снимках экрана чёрные).

## Структура (`app/src/main/java/app/brainfit`)

```
core/designsystem/   Palette (токены, чистый Kotlin), Theme, Components
core/time/           MonotonicClock, WallClock, Stopwatch, FrameClock, LocalDays (местные даты)
core/sensors/        ShakeMeter, ShakeConfig (чистый Kotlin), SensorShakeMonitor
core/util/           Json (запись JSON без библиотек)
games/engine/        Game, GameSession, TrialResult, SessionScore, LevelConfig, Fmt, Stats (медиана, d′)
games/schulte|mathsprint|corsi|stroop|nback|doubledecision/   логика игр
data/db/             Entities, Daos, BrainFitDatabase (+ Migrations)
data/repository/     реализации репозиториев
domain/model/        перечисления, AppSettings, GameCatalog, модели сессий, уровней и тренировок
domain/adaptive/     Adaptation, AdaptiveConfig (калибровка и окно)
domain/workout/      WorkoutPlanner (подбор игр)
domain/progress/     Progress (рекорды, серия, история уровней, сравнение), ProgressMetrics
domain/repository/   интерфейсы репозиториев
di/                  AppModule (Room, DataStore), RepositoryModule
feature/             navigation, home, games, progress, settings, workout, play (хост игры, play/game — экраны раундов)
```

Тесты: `src/test` — JVM, `src/androidTest` — на устройстве, `src/sharedTest` — игра-заглушка `TapGame` и подделки для обоих.

## Правила

- `domain/` и будущий `games/` — чистый Kotlin без Android, тестируются на JVM.
- Никаких фейковых данных и кнопок-пустышек. Нет данных — так и писать.
- Без сети: разрешения `INTERNET` нет и быть не должно.
- Схема базы меняется только миграцией (`version + 1`, `Migrations.ALL`, тест). Имена enum-констант не менять.
- Ошибки сохранения не глотать — показывать пользователю.
- Цвета — из `BrainTheme.colors` и `MaterialTheme`, новые пары «текст — фон» добавлять в `PaletteContrastTest`.
- Цели касания не меньше 48 dp, анимации интерфейса не длиннее 200 мс.
- Пользователь пишет по-русски и просит общаться просто, по-дружески.

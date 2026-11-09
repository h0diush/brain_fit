# BrainFit — контекст для Claude Code

Офлайн-приложение для Android: шесть коротких игр на память, внимание и скорость. ТЗ — `BrainFit v0.1 — ТЗ.md`
(раздел 13 — правила работы, раздел 10 — этапы). Решения и открытые вопросы — `docs/decisions.md`.

## Где мы

Сделаны этап 1 (фундамент) и этап 2 (игровой движок: контракт `Game`, `GameSession`, `PlayController`,
экран-хост, запись сессий, детектор тряски; Room версии 2). Игр ещё нет — `GameRegistry` пуст.
Дальше этап 3 — Шульте, математический спринт, Корси. Работать строго по этапам, после каждого — сборка, тесты, отчёт.

Новая игра = логика в `games/<имя>/` (реализация `Game`, чистый Kotlin) + экран раунда и строка в
`GameRegistry.ALL` (`feature/play`). Паузу, раунды, запись и результат делает хост — в игре их не повторять.

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
core/time/           MonotonicClock, WallClock, Stopwatch, FrameClock
core/sensors/        ShakeMeter, ShakeConfig (чистый Kotlin), SensorShakeMonitor
core/util/           Json (запись JSON без библиотек)
games/engine/        Game, GameSession, TrialResult, SessionScore, LevelConfig
data/db/             Entities, Daos, BrainFitDatabase (+ Migrations)
data/repository/     реализации репозиториев
domain/model/        перечисления, AppSettings, GameCatalog
domain/repository/   интерфейсы репозиториев
di/                  AppModule (Room, DataStore), RepositoryModule
feature/             navigation, home, games, progress, settings, play (хост игры)
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

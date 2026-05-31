# Запуск MVP на телефоне

Эта инструкция для текущего Android MVP.

## Что уже есть

- Kotlin Android-приложение.
- Две темы.
- Главный экран заметок.
- Создание заметки.
- Экран редактора.
- Камерный контейнер.
- CameraX preview.
- OCR через ML Kit Text Recognition.
- Русский OCR fallback через Tesseract `rus+eng`.
- Скачивание OCR-модуля через Google Play Services.
- Скачивание Gemma 4 E2B `.litertlm`.
- Запуск Gemma через LiteRT-LM в отдельном процессе `:gemma`.
- Потоковая генерация Markdown через Gemma.
- Markdown-блоки с preview и edit sheet.
- Raw draft card в камерном слое.

## Первый запуск

Приложение сначала показывает экран моделей.

Кнопка `Скачать модели` делает две вещи:

1. Ставит OCR-модуль ML Kit.
2. Скачивает `rus.traineddata` и `eng.traineddata` для Tesseract.
3. Скачивает `gemma-4-E2B-it.litertlm`.

Файл Gemma весит примерно 2.41 GiB. Для первого запуска лучше включить Wi-Fi и держать телефон на зарядке.

После скачивания модель лежит во внутреннем хранилище приложения:

```text
files/models/gemma-4-e2b-it/main/gemma-4-E2B-it.litertlm
```

Обычные обновления APK не должны скачивать её заново.

## Сборка

В репозитории уже есть Gradle wrapper.

```powershell
.\gradlew.bat :app:assembleDebug
```

Unit-тесты:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

На Windows проект может лежать в пути с кириллицей. Для `testDebugUnitTest` Gradle копирует project output во временный ASCII-путь внутри `~/.gradle/osnova-ai-testpath`, иначе Java launcher может потерять test classpath через argfile.

Lint:

```powershell
.\gradlew.bat :app:lintDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Установка

Если `adb` есть в PATH:

```powershell
adb devices
.\gradlew.bat :app:installDebug
```

Если `adb` не в PATH, используй установленный SDK:

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices
.\gradlew.bat :app:installDebug
```

Или поставить уже собранный APK:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r .\app\build\outputs\apk\debug\app-debug.apk
```

`-r` сохраняет данные приложения. Это важно: Gemma не будет скачиваться заново после каждого обновления.

На Vivo может появиться системное подтверждение установки по USB. Если команда падает с `INSTALL_FAILED_ABORTED: User rejected permissions`, APK собран нормально, но телефон отклонил установку. Нужно разрешить установку на экране телефона и повторить `.\gradlew.bat :app:installDebug`.

## Проверка сценария

1. Открыть OSNOVA AI.
2. Нажать `Скачать модели`, если экран моделей появился.
3. Дождаться готовности OCR и Gemma.
4. Открыть заметку.
5. Нажать `камера`.
6. Дать разрешение на камеру.
7. Навести на русский или смешанный русский/английский текст.
8. Дождаться очереди `OCR`, затем raw draft card.
9. Проверить, что Gemma начинает дописывать Markdown-блок постепенно.
10. Закрыть камеру во время генерации.
11. Убедиться, что preview закрылся, но блок продолжил дописываться.
12. Открыть блок тапом и проверить edit sheet с Markdown-панелью.
13. Повторно открыть камеру и проверить, что следующий близкий по смыслу текст не дублирует блок.

Если Gemma не успела ответить или LiteRT-LM вернул ошибку, приложение вставит OCR-текст как запасной результат. Экран заметки не должен закрываться из-за ошибки модельного процесса.

## Проверка на Vivo X200 Ultra

Целевой телефон для первых ручных тестов: Vivo X200 Ultra на Snapdragon 8 Elite.

Чеклист:

- первый запуск видит модели или предлагает скачать;
- камера открывается без падения;
- русский печатный текст проходит через Tesseract `rus+eng`;
- raw draft появляется в камерном слое;
- Gemma стримит Markdown через `delta`-события;
- закрытие камеры не отменяет активную генерацию;
- повторное открытие камеры создаёт следующий блок;
- Markdown preview и edit sheet работают;
- приложение не скачивает Gemma заново после `installDebug`.

## Если нужно начать с нуля

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell pm clear ai.osnova.app
```

После этого приложение снова попросит скачать модели.

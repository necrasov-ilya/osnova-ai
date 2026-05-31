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
- Скачивание OCR-модуля через Google Play Services.
- Скачивание Gemma 4 E2B `.litertlm` под Snapdragon 8 Elite.
- Попытка запуска Gemma через LiteRT-LM.
- Вставка распознанного блока в заметку.

## Первый запуск

Приложение сначала показывает экран моделей.

Кнопка `Скачать модели` делает две вещи:

1. Ставит OCR-модуль ML Kit.
2. Скачивает `gemma-4-E2B-it_qualcomm_sm8750.litertlm`.

Файл Gemma весит примерно 2.8 GiB. Для первого запуска лучше включить Wi-Fi и держать телефон на зарядке.

После скачивания модель лежит во внутреннем хранилище приложения:

```text
files/models/gemma-4-e2b-it-sm8750/main/gemma-4-E2B-it_qualcomm_sm8750.litertlm
```

Обычные обновления APK не должны скачивать её заново.

## Сборка

В репозитории уже есть Gradle wrapper.

```powershell
.\gradlew.bat :app:assembleDebug
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

## Проверка сценария

1. Открыть OSNOVA AI.
2. Нажать `Скачать модели`, если экран моделей появился.
3. Дождаться готовности OCR и Gemma.
4. Открыть заметку.
5. Нажать `камера`.
6. Дать разрешение на камеру.
7. Навести на текст.
8. Дождаться очереди `OCR` и блока в заметке.
9. Закрыть камеру и проверить, что текст остался в редакторе.

## Если нужно начать с нуля

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell pm clear ai.osnova.app
```

После этого приложение снова попросит скачать модели.

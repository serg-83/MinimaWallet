# MinimaWallet

> Android wallet application for the [Minima](https://minima.global) blockchain network.
>
> Android-кошелёк для блокчейн-сети [Minima](https://minima.global).

---

## English

### About
MinimaWallet is a native Android application for managing keys and interacting with the Minima blockchain network. It provides a clean Material Design interface with a Navigation Drawer for easy navigation between sections.

### Features
- **Wallet** — view balance, generate cryptographic keys
- **Send** — send transactions to the Minima network
- **Seed Phrase** — securely store and manage your seed phrase
- **Settings** — configure node URL and application preferences
- **Biometric protection** — fingerprint authentication on startup if a seed phrase is saved
- **Secure storage** — seed phrase is stored encrypted using Android Keystore

### Architecture
- Language: **Java**
- Min SDK: **26 (Android 8.0)**
- Target SDK: **33 (Android 13)**
- UI: **Material Design 3**, Navigation Drawer, ViewBinding
- Security: **SecureRandom**, Android Keystore, BiometricPrompt
- Background work: **ExecutorService** (no deprecated AsyncTask)

### Project Structure
```
app/src/main/java/com/example/minimawallet/
├── MainActivity.java        # Main activity, Navigation Drawer
├── WalletFragment.java      # Wallet tab: balance and key generation
├── SendFragment.java        # Send transactions
├── SeedPhraseFragment.java  # Seed phrase management
├── SettingsFragment.java    # Settings
├── WalletViewModel.java     # Shared state between fragments (LiveData)
├── KeyGenerator.java        # Cryptographic key generation (SecureRandom)
└── SecureStorage.java       # Encrypted storage via Android Keystore
```

### Building

#### Debug build (local)
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

#### Release build (local)
Create `keystore.properties` in the project root (this file is NOT committed to git):
```properties
storeFile=release-keystore.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```
Then run:
```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

#### CI/CD (GitHub Actions)
Release builds are automatically triggered on push to `main`. The keystore is stored as GitHub Secrets (never in the repository). See [.github/workflows/build.yml](.github/workflows/build.yml).

### Security Notes
- `*.jks` and `keystore.properties` are excluded from git via `.gitignore`
- Never commit your signing keys or passwords to version control
- Seed phrase is stored only in encrypted Android Keystore storage, never in plain text

### Requirements
- Android Studio or Gradle 8.5+
- JDK 17+
- Android SDK (compileSdk 33)

---

## Русский

### О проекте
MinimaWallet — нативное Android-приложение для управления ключами и взаимодействия с блокчейн-сетью Minima. Реализован чистый интерфейс в стиле Material Design с боковым навигационным меню (Navigation Drawer).

### Функциональность
- **Кошелёк** — просмотр баланса, генерация криптографических ключей
- **Отправка** — отправка транзакций в сеть Minima
- **Seed-фраза** — безопасное хранение и управление сид-фразой
- **Настройки** — настройка URL узла и параметров приложения
- **Биометрическая защита** — при запуске запрашивает отпечаток пальца, если сид-фраза сохранена
- **Безопасное хранилище** — сид-фраза хранится в зашифрованном виде через Android Keystore

### Архитектура
- Язык: **Java**
- Минимальный SDK: **26 (Android 8.0)**
- Целевой SDK: **33 (Android 13)**
- UI: **Material Design 3**, Navigation Drawer, ViewBinding
- Безопасность: **SecureRandom**, Android Keystore, BiometricPrompt
- Фоновые задачи: **ExecutorService** (без устаревшего AsyncTask)

### Структура проекта
```
app/src/main/java/com/example/minimawallet/
├── MainActivity.java        # Главная активность, Navigation Drawer
├── WalletFragment.java      # Вкладка кошелька: баланс и генерация ключей
├── SendFragment.java        # Отправка транзакций
├── SeedPhraseFragment.java  # Управление сид-фразой
├── SettingsFragment.java    # Настройки
├── WalletViewModel.java     # Общее состояние между фрагментами (LiveData)
├── KeyGenerator.java        # Генерация криптографических ключей (SecureRandom)
└── SecureStorage.java       # Зашифрованное хранилище через Android Keystore
```

### Сборка

#### Debug-сборка (локально)
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

#### Release-сборка (локально)
Создайте файл `keystore.properties` в корне проекта (он НЕ попадает в git):
```properties
storeFile=release-keystore.jks
storePassword=ВАШ_ПАРОЛЬ_ХРАНИЛИЩА
keyAlias=ВАШ_АЛИАС_КЛЮЧА
keyPassword=ВАШ_ПАРОЛЬ_КЛЮЧА
```
Затем запустите:
```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

#### CI/CD (GitHub Actions)
Release-сборка запускается автоматически при пуше в ветку `main`. Keystore хранится в GitHub Secrets (никогда не в репозитории). См. [.github/workflows/build.yml](.github/workflows/build.yml).

### Безопасность
- `*.jks` и `keystore.properties` исключены из git через `.gitignore`
- Никогда не коммитьте ключи подписи или пароли в систему контроля версий
- Сид-фраза хранится только в зашифрованном хранилище Android Keystore, никогда в открытом виде

### Требования
- Android Studio или Gradle 8.5+
- JDK 17+
- Android SDK (compileSdk 33)

---

## License / Лицензия

MIT License — see [LICENSE](LICENSE)

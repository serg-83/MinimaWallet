<div align="center">

# MinimaWallet

**Нативный Android-кошелёк для блокчейн-сети [Minima](https://minima.global)**
**Native Android wallet for the [Minima](https://minima.global) blockchain network**

[![Build APK](https://github.com/serg-83/MinimaWallet/actions/workflows/build.yml/badge.svg)](https://github.com/serg-83/MinimaWallet/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/serg-83/MinimaWallet?color=0D9488)](https://github.com/serg-83/MinimaWallet/releases/latest)
[![Min SDK](https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android)](https://developer.android.com/about/versions/oreo)
[![Language](https://img.shields.io/badge/Java-8-orange?logo=openjdk)](https://openjdk.org)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

[**Скачать APK**](https://github.com/serg-83/MinimaWallet/releases/latest) · [Releases](https://github.com/serg-83/MinimaWallet/releases)

</div>

---

## Русский

### О проекте

MinimaWallet — нативное Android-приложение для управления ключами и взаимодействия с блокчейн-сетью Minima. Реализован чистый интерфейс в стиле **Material Design 3** с боковым навигационным меню (Navigation Drawer) и биометрической защитой.

### Возможности

| Раздел | Описание |
|---|---|
| 💰 **Кошелёк** | Просмотр баланса, генерация криптографических ключей и адресов |
| 📤 **Отправка** | Отправка транзакций в сеть Minima |
| 🔑 **Seed-фраза** | Безопасное хранение и управление сид-фразой (BIP39) |
| ⚙️ **Настройки** | Настройка URL узла, выбор языка интерфейса |
| 🔒 **Биометрия** | Аутентификация по отпечатку пальца при запуске |

### Безопасность

- Сид-фраза хранится **только** в зашифрованном хранилище Android Keystore (AES-256-GCM)
- Генерация ключей на базе **SecureRandom** (без java.util.Random)
- Биометрическая защита через **BiometricPrompt**
- Запрещён cleartext-трафик (`network_security_config.xml`)

### Архитектура

```
app/src/main/java/com/example/minimawallet/
├── MainActivity.java        # Главная активность, Navigation Drawer
├── WalletFragment.java      # Баланс и генерация ключей
├── SendFragment.java        # Отправка транзакций
├── SeedPhraseFragment.java  # Управление сид-фразой
├── SettingsFragment.java    # Настройки
├── WalletViewModel.java     # Общее состояние (LiveData)
├── KeyGenerator.java        # Генерация ключей (SecureRandom + ExecutorService)
└── SecureStorage.java       # Зашифрованное хранилище (Android Keystore)
```

**Стек:** Java · Material Design 3 · ViewBinding · ViewModel/LiveData · BiometricPrompt · Android Keystore

### Установка

Скачайте `app-release.apk` из [Releases](https://github.com/serg-83/MinimaWallet/releases/latest) и установите на Android-устройство.

> ⚠️ Разрешите установку из неизвестных источников в настройках Android.

### Сборка

<details>
<summary>Debug (локально)</summary>

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
</details>

<details>
<summary>Release (локально)</summary>

Создайте `keystore.properties` в корне проекта (не попадает в git):
```properties
storeFile=release-keystore.jks
storePassword=ВАШ_ПАРОЛЬ
keyAlias=ВАШ_АЛИАС
keyPassword=ВАШ_ПАРОЛЬ_КЛЮЧА
```
```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```
</details>

<details>
<summary>CI/CD (GitHub Actions)</summary>

Release-сборка запускается автоматически при пуше тега `v*.*.*`. Keystore хранится в GitHub Secrets — никогда не в репозитории. См. [build.yml](.github/workflows/build.yml).
</details>

---

## English

### About

MinimaWallet is a native Android application for managing keys and interacting with the Minima blockchain network. Built with **Material Design 3**, Navigation Drawer navigation, and biometric protection.

### Features

| Section | Description |
|---|---|
| 💰 **Wallet** | View balance, generate cryptographic keys and addresses |
| 📤 **Send** | Send transactions to the Minima network |
| 🔑 **Seed Phrase** | Securely store and manage your seed phrase (BIP39) |
| ⚙️ **Settings** | Configure node URL and language preference |
| 🔒 **Biometrics** | Fingerprint authentication on startup |

### Security

- Seed phrase stored **exclusively** in Android Keystore encrypted storage (AES-256-GCM)
- Key generation using **SecureRandom** (no java.util.Random)
- Biometric authentication via **BiometricPrompt**
- Cleartext traffic blocked via `network_security_config.xml`

### Tech Stack

**Java** · Material Design 3 · ViewBinding · ViewModel/LiveData · BiometricPrompt · Android Keystore
Min SDK: **26 (Android 8.0)** · Target SDK: **33 (Android 13)**

### Build

<details>
<summary>Debug (local)</summary>

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
</details>

<details>
<summary>Release (local)</summary>

Create `keystore.properties` in the project root (excluded from git):
```properties
storeFile=release-keystore.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```
```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```
</details>

<details>
<summary>CI/CD (GitHub Actions)</summary>

Release APK is built automatically on `v*.*.*` tag push. Keystore is stored in GitHub Secrets — never in the repository. See [build.yml](.github/workflows/build.yml).
</details>

---

## License / Лицензия

[MIT License](LICENSE)

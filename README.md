<div align="center">

# MinimaWallet

**Native Android wallet for the [Minima](https://minima.global) blockchain network**
**Нативный Android-кошелёк для блокчейн-сети [Minima](https://minima.global)**

[![Build APK](https://github.com/serg-83/MinimaWallet/actions/workflows/build.yml/badge.svg)](https://github.com/serg-83/MinimaWallet/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/serg-83/MinimaWallet?color=0D9488)](https://github.com/serg-83/MinimaWallet/releases/latest)
[![Min SDK](https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android)](https://developer.android.com/about/versions/oreo)
[![Language](https://img.shields.io/badge/Java-8-orange?logo=openjdk)](https://openjdk.org)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

[**Download APK**](https://github.com/serg-83/MinimaWallet/releases/latest) · [Releases](https://github.com/serg-83/MinimaWallet/releases)

</div>

---

## English

### About

MinimaWallet is a native Android application for managing keys and interacting with the Minima blockchain network. **No node required.** Built with **Material Design 3**, Navigation Drawer navigation, and biometric protection.

### How it works

```
Seed Phrase → [locally] → Private Key + Address
                                  │
            ┌─────────────────────┼─────────────────────┐
            ▼                     ▼                     ▼
     Request balance      Get unsigned transaction   Broadcast signed
    wallet.minima.global   wallet.minima.global      transaction
          (API)                  (API)            wallet.minima.global
                                  │                    (API)
                                  ▼
                       [locally, no internet]
                        Sign transaction with
                           private key
```

> 🔐 **The private key never leaves the device.** Signing happens locally without transmitting the key over the network. Only public data is sent via API: address, balance, unsigned and signed transaction.

### Features

| Section | Description |
|---|---|
| 💰 **Wallet** | View balance, generate cryptographic keys and addresses |
| 📤 **Send** | Send transactions to the Minima network with token selector |
| 🔑 **Seed Phrase** | Securely store and manage your seed phrase (BIP39) |
| ⚙️ **Settings** | Configure node URL |
| 🔒 **Biometrics** | Fingerprint authentication on startup |

### Security

- **No node required** — the app works via the public API `wallet.minima.global`
- **Keys on-the-fly** — private key is derived from the seed phrase each time and never stored
- **Offline signing** — transactions are signed locally on the device, the key is never sent over the network
- Seed phrase stored **exclusively** in Android Keystore encrypted storage (AES-256-GCM)
- Key generation using **SecureRandom** (no java.util.Random)
- Biometric authentication via **BiometricPrompt**
- Cleartext traffic blocked via `network_security_config.xml`

### Architecture

```
app/src/main/java/com/example/minimawallet/
├── MainActivity.java        # Main activity, Navigation Drawer
├── WalletFragment.java      # Balance, key generation, token list
├── SendFragment.java        # Send transactions with token selector
├── SeedPhraseFragment.java  # Seed phrase management
├── SettingsFragment.java    # Settings
├── LogFragment.java         # Server response log
├── WalletViewModel.java     # Shared state (LiveData)
├── KeyGenerator.java        # Key generation (SecureRandom + ExecutorService)
└── SecureStorage.java       # Encrypted storage (Android Keystore)
```

**Stack:** Java · Material Design 3 · ViewBinding · ViewModel/LiveData · BiometricPrompt · Android Keystore
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

Release APK is built automatically on every push to `main`. Version is read from `versionName` in `build.gradle`. Keystore is stored in GitHub Secrets — never in the repository. See [build.yml](.github/workflows/build.yml).
</details>

---

## Русский

### О проекте

MinimaWallet — нативное Android-приложение для управления ключами и взаимодействия с блокчейн-сетью Minima. **Не требует запуска собственной ноды.** Реализован чистый интерфейс в стиле **Material Design 3** с боковым навигационным меню (Navigation Drawer) и биометрической защитой.

### Как это работает

```
Seed-фраза → [локально] → Приватный ключ + Адрес
                                    │
              ┌─────────────────────┼─────────────────────┐
              ▼                     ▼                     ▼
     Запрос баланса       Получение неподписанной    Отправка подписанной
    wallet.minima.global      транзакции              транзакции
         (API)             wallet.minima.global     wallet.minima.global
                                (API)                    (API)
                                    │
                                    ▼
                          [локально, без интернета]
                            Подписание транзакции
                            приватным ключом
```

> 🔐 **Приватный ключ никогда не покидает устройство.** Подписание происходит локально, без передачи ключа в сеть. Через API передаются только публичные данные: адрес, баланс, неподписанная и подписанная транзакция.

### Возможности

| Раздел | Описание |
|---|---|
| 💰 **Кошелёк** | Просмотр баланса, генерация криптографических ключей и адресов |
| 📤 **Отправка** | Отправка транзакций с выбором токена |
| 🔑 **Seed-фраза** | Безопасное хранение и управление сид-фразой (BIP39) |
| ⚙️ **Настройки** | Настройка URL узла |
| 🔒 **Биометрия** | Аутентификация по отпечатку пальца при запуске |

### Безопасность

- **Нода не нужна** — приложение работает через публичный API `wallet.minima.global`
- **Ключи на лету** — приватный ключ генерируется из seed-фразы каждый раз заново и нигде не сохраняется
- **Подписание офлайн** — транзакция подписывается локально на устройстве без передачи ключа в сеть
- Сид-фраза хранится **только** в зашифрованном хранилище Android Keystore (AES-256-GCM)
- Генерация ключей на базе **SecureRandom** (без java.util.Random)
- Биометрическая защита через **BiometricPrompt**
- Запрещён cleartext-трафик (`network_security_config.xml`)

### Установка

Скачайте APK из [Releases](https://github.com/serg-83/MinimaWallet/releases/latest) и установите на Android-устройство.

> ⚠️ Разрешите установку из неизвестных источников в настройках Android.

---

## License / Лицензия

[MIT License](LICENSE)

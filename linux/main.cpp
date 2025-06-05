#include <QSettings>
#include <QLocalServer>
#include <QLocalSocket>
#include <QApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QBluetoothLocalDevice>
#include <QBluetoothSocket>
#include <QQuickWindow>
#include <QLoggingCategory>
#include <QThread>
#include <QTimer>
#include <QProcess>

#include "airpods_packets.h"
#include "logger.h"
#include "mediacontroller.h"
#include "trayiconmanager.h"
#include "enums.h"
#include "battery.hpp"
#include "BluetoothMonitor.h"
#include "autostartmanager.hpp"
#include "deviceinfo.hpp"
#include "ble/blemanager.h"
#include "ble/bleutils.h"
#include "QRCodeImageProvider.hpp"

using namespace AirpodsTrayApp::Enums;

Q_LOGGING_CATEGORY(airpodsApp, "airpodsApp")

class AirPodsTrayApp : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool airpodsConnected READ areAirpodsConnected NOTIFY airPodsStatusChanged)
    Q_PROPERTY(int earDetectionBehavior READ earDetectionBehavior WRITE setEarDetectionBehavior NOTIFY earDetectionBehaviorChanged)
    Q_PROPERTY(bool crossDeviceEnabled READ crossDeviceEnabled WRITE setCrossDeviceEnabled NOTIFY crossDeviceEnabledChanged)
    Q_PROPERTY(AutoStartManager *autoStartManager READ autoStartManager CONSTANT)
    Q_PROPERTY(bool notificationsEnabled READ notificationsEnabled WRITE setNotificationsEnabled NOTIFY notificationsEnabledChanged)
    Q_PROPERTY(int retryAttempts READ retryAttempts WRITE setRetryAttempts NOTIFY retryAttemptsChanged)
    Q_PROPERTY(bool hideOnStart READ hideOnStart CONSTANT)
    Q_PROPERTY(DeviceInfo *deviceInfo READ deviceInfo CONSTANT)

public:
    AirPodsTrayApp(bool debugMode, bool hideOnStart, QQmlApplicationEngine *parent = nullptr)
        : QObject(parent), debugMode(debugMode), m_settings(new QSettings("AirPodsTrayApp", "AirPodsTrayApp"))
        , m_autoStartManager(new AutoStartManager(this)), m_hideOnStart(hideOnStart), parent(parent)
        , m_deviceInfo(new DeviceInfo(this)), m_bleManager(new BleManager(this))
    {
        QLoggingCategory::setFilterRules(QString("airpodsApp.debug=%1").arg(debugMode ? "true" : "false"));
        LOG_INFO("Initializing AirPodsTrayApp");

        // Initialize tray icon and connect signals
        trayManager = new TrayIconManager(this);
        trayManager->setNotificationsEnabled(loadNotificationsEnabled());
        connect(trayManager, &TrayIconManager::trayClicked, this, &AirPodsTrayApp::onTrayIconActivated);
        connect(trayManager, &TrayIconManager::openApp, this, &AirPodsTrayApp::onOpenApp);
        connect(trayManager, &TrayIconManager::openSettings, this, &AirPodsTrayApp::onOpenSettings);
        connect(trayManager, &TrayIconManager::noiseControlChanged, this, &AirPodsTrayApp::setNoiseControlMode);
        connect(trayManager, &TrayIconManager::conversationalAwarenessToggled, this, &AirPodsTrayApp::setConversationalAwareness);
        connect(m_deviceInfo, &DeviceInfo::batteryStatusChanged, trayManager, &TrayIconManager::updateBatteryStatus);
        connect(m_deviceInfo, &DeviceInfo::noiseControlModeChanged, trayManager, &TrayIconManager::updateNoiseControlState);
        connect(m_deviceInfo, &DeviceInfo::conversationalAwarenessChanged, trayManager, &TrayIconManager::updateConversationalAwareness);
        connect(trayManager, &TrayIconManager::notificationsEnabledChanged, this, &AirPodsTrayApp::saveNotificationsEnabled);
        connect(trayManager, &TrayIconManager::notificationsEnabledChanged, this, &AirPodsTrayApp::notificationsEnabledChanged);

        // Initialize MediaController and connect signals
        mediaController = new MediaController(this);
        connect(m_deviceInfo, &DeviceInfo::earDetectionStatusChanged, mediaController, &MediaController::handleEarDetection);
        connect(mediaController, &MediaController::mediaStateChanged, this, &AirPodsTrayApp::handleMediaStateChange);
        mediaController->followMediaChanges();

        monitor = new BluetoothMonitor(this);
        connect(monitor, &BluetoothMonitor::deviceConnected, this, &AirPodsTrayApp::bluezDeviceConnected);
        connect(monitor, &BluetoothMonitor::deviceDisconnected, this, &AirPodsTrayApp::bluezDeviceDisconnected);

        connect(m_bleManager, &BleManager::deviceFound, this, &AirPodsTrayApp::bleDeviceFound);
        connect(m_deviceInfo->getBattery(), &Battery::primaryChanged, this, &AirPodsTrayApp::primaryChanged);

        // Load settings
        CrossDevice.isEnabled = loadCrossDeviceEnabled();
        setEarDetectionBehavior(loadEarDetectionSettings());
        setRetryAttempts(loadRetryAttempts());

        monitor->checkAlreadyConnectedDevices();
        LOG_INFO("AirPodsTrayApp initialized");

        QBluetoothLocalDevice localDevice;

        const QList<QBluetoothAddress> connectedDevices = localDevice.connectedDevices();
        for (const QBluetoothAddress &address : connectedDevices) {
            QBluetoothDeviceInfo device(address, "", 0);
            if (isAirPodsDevice(device)) {
                connectToDevice(device);
                return;
            }
        }

        initializeDBus();
        initializeBluetooth();
    }

    ~AirPodsTrayApp() {
        saveCrossDeviceEnabled();
        saveEarDetectionSettings();

        delete socket;
        delete phoneSocket;
    }

    bool areAirpodsConnected() const { return socket && socket->isOpen() && socket->state() == QBluetoothSocket::SocketState::ConnectedState; }
    int earDetectionBehavior() const { return mediaController->getEarDetectionBehavior(); }
    bool crossDeviceEnabled() const { return CrossDevice.isEnabled; }
    AutoStartManager *autoStartManager() const { return m_autoStartManager; }
    bool notificationsEnabled() const { return trayManager->notificationsEnabled(); }
    void setNotificationsEnabled(bool enabled) { trayManager->setNotificationsEnabled(enabled); }
    int retryAttempts() const { return m_retryAttempts; }
    bool hideOnStart() const { return m_hideOnStart; }
    DeviceInfo *deviceInfo() const { return m_deviceInfo; }

private:
    bool debugMode;
    bool isConnectedLocally = false;

    QQmlApplicationEngine *parent = nullptr;

    struct {
        bool isAvailable = true;
        bool isEnabled = true; // Ability to disable the feature
    } CrossDevice;

    void initializeDBus() { }

    bool isAirPodsDevice(const QBluetoothDeviceInfo &device)
    {
        return device.serviceUuids().contains(QBluetoothUuid("74ec2172-0bad-4d01-8f77-997b2be0722a"));
    }

    void notifyAndroidDevice()
    {
        if (!CrossDevice.isEnabled) {
            return;
        }

        if (phoneSocket && phoneSocket->isOpen())
        {
            phoneSocket->write(AirPodsPackets::Phone::NOTIFICATION);
            LOG_DEBUG("Sent notification packet to Android: " << AirPodsPackets::Phone::NOTIFICATION.toHex());
        }
        else
        {
            LOG_WARN("Phone socket is not open, cannot send notification packet");
        }
    }

    void disconnectDevice(const QString &devicePath) {
        LOG_INFO("Disconnecting device at " << devicePath);
    }

public slots:
    void connectToDevice(const QString &address) {
        LOG_INFO("Connecting to device with address: " << address);
        QBluetoothAddress btAddress(address);
        QBluetoothDeviceInfo device(btAddress, "", 0);
        connectToDevice(device);
    }

    void setNoiseControlMode(NoiseControlMode mode)
    {
        if (m_deviceInfo->noiseControlMode() == mode)
        {
            LOG_INFO("Noise control mode is already set to: " << static_cast<int>(mode));
            return;
        }
        LOG_INFO("Setting noise control mode to: " << mode);
        QByteArray packet = AirPodsPackets::NoiseControl::getPacketForMode(mode);
        writePacketToSocket(packet, "Noise control mode packet written: ");
    }
    void setNoiseControlModeInt(int mode)
    {
        if (mode < 0 || mode > static_cast<int>(NoiseControlMode::Adaptive))
        {
            LOG_ERROR("Invalid noise control mode: " << mode);
            return;
        }
        setNoiseControlMode(static_cast<NoiseControlMode>(mode));
    }

    void setConversationalAwareness(bool enabled)
    {
        LOG_INFO("Setting conversational awareness to: " << (enabled ? "enabled" : "disabled"));
        QByteArray packet = enabled ? AirPodsPackets::ConversationalAwareness::ENABLED
                                    : AirPodsPackets::ConversationalAwareness::DISABLED;

        writePacketToSocket(packet, "Conversational awareness packet written: ");
        m_deviceInfo->setConversationalAwareness(enabled);
    }

    void setOneBudANCMode(bool enabled)
    {
        if (m_deviceInfo->oneBudANCMode() == enabled)
        {
            LOG_INFO("One Bud ANC mode is already " << (enabled ? "enabled" : "disabled"));
            return;
        }

        LOG_INFO("Setting One Bud ANC mode to: " << (enabled ? "enabled" : "disabled"));
        QByteArray packet = enabled ? AirPodsPackets::OneBudANCMode::ENABLED
                                    : AirPodsPackets::OneBudANCMode::DISABLED;

        if (writePacketToSocket(packet, "One Bud ANC mode packet written: "))
        {
            m_deviceInfo->setOneBudANCMode(enabled);
        }
        else
        {
            LOG_ERROR("Failed to send One Bud ANC mode command: socket not open");
        }
    }

    void setRetryAttempts(int attempts)
    {
        if (m_retryAttempts != attempts)
        {
            LOG_DEBUG("Setting retry attempts to: " << attempts);
            m_retryAttempts = attempts;
            emit retryAttemptsChanged(attempts);
            saveRetryAttempts(attempts);
        }
    }

    void initiateMagicPairing()
    {
        if (!socket || !socket->isOpen())
        {
            LOG_ERROR("Socket nicht offen, Magic Pairing kann nicht gestartet werden");
            return;
        }

        writePacketToSocket(AirPodsPackets::MagicPairing::REQUEST_MAGIC_CLOUD_KEYS, "Magic Pairing packet written: ");
    }

    void setAdaptiveNoiseLevel(int level)
    {
        level = qBound(0, level, 100);
        if (m_deviceInfo->adaptiveNoiseLevel() != level && m_deviceInfo->adaptiveModeActive())
        {
            QByteArray packet = AirPodsPackets::AdaptiveNoise::getPacket(level);
            writePacketToSocket(packet, "Adaptive noise level packet written: ");
            m_deviceInfo->setAdaptiveNoiseLevel(level);
        }
    }

    void renameAirPods(const QString &newName)
    {
        if (newName.isEmpty())
        {
            LOG_WARN("Cannot set empty name");
            return;
        }
        if (newName.size() > 32)
        {
            LOG_WARN("Name is too long, must be 32 characters or less");
            return;
        }
        if (newName == m_deviceInfo->deviceName())
        {
            LOG_INFO("Name is already set to: " << newName);
            return;
        }

        QByteArray packet = AirPodsPackets::Rename::getPacket(newName);
        if (writePacketToSocket(packet, "Rename packet written: "))
        {
            LOG_INFO("Sent rename command for new name: " << newName);
            m_deviceInfo->setDeviceName(newName);
        }
        else
        {
            LOG_ERROR("Failed to send rename command: socket not open");
        }
    }

    void setEarDetectionBehavior(int behavior)
    {
        if (behavior == earDetectionBehavior())
        {
            LOG_INFO("Ear detection behavior is already set to: " << behavior);
            return;
        }

        mediaController->setEarDetectionBehavior(static_cast<MediaController::EarDetectionBehavior>(behavior));
        saveEarDetectionSettings();
        emit earDetectionBehaviorChanged(behavior);
    }

    void setCrossDeviceEnabled(bool enabled)
    {
        if (CrossDevice.isEnabled == enabled)
        {
            LOG_INFO("Cross-device feature is already " << (enabled ? "enabled" : "disabled"));
            return;
        }

        CrossDevice.isEnabled = enabled;
        saveCrossDeviceEnabled();
        connectToPhone();
        emit crossDeviceEnabledChanged(enabled);
    }

    bool writePacketToSocket(const QByteArray &packet, const QString &logMessage)
    {
        if (socket && socket->isOpen())
        {
            socket->write(packet);
            LOG_DEBUG(logMessage << packet.toHex());
            return true;
        }
        else
        {
            LOG_ERROR("Socket is not open, cannot write packet");
            return false;
        }
    }

    bool loadCrossDeviceEnabled() { return m_settings->value("crossdevice/enabled", false).toBool(); }
    void saveCrossDeviceEnabled() { m_settings->setValue("crossdevice/enabled", CrossDevice.isEnabled); }

    int loadEarDetectionSettings() { return m_settings->value("earDetection/setting", MediaController::EarDetectionBehavior::PauseWhenOneRemoved).toInt(); }
    void saveEarDetectionSettings() { m_settings->setValue("earDetection/setting", mediaController->getEarDetectionBehavior()); }

    bool loadNotificationsEnabled() const { return m_settings->value("notifications/enabled", true).toBool(); }
    void saveNotificationsEnabled(bool enabled) { m_settings->setValue("notifications/enabled", enabled); }

    int loadRetryAttempts() const { return m_settings->value("bluetooth/retryAttempts", 3).toInt(); }
    void saveRetryAttempts(int attempts) { m_settings->setValue("bluetooth/retryAttempts", attempts); }

private slots:
    void onTrayIconActivated()
    {
        QQuickWindow *window = qobject_cast<QQuickWindow *>(
            QGuiApplication::topLevelWindows().constFirst());
        if (window)
        {
            window->show();
            window->raise();
            window->requestActivate();
        }
    }

    void onOpenApp()
    {
        QObject *rootObject = parent->rootObjects().first();
        if (rootObject) {
            QMetaObject::invokeMethod(rootObject, "reopen", Q_ARG(QVariant, "app"));
        }
        else
        {
            loadMainModule();
        }
    }

    void onOpenSettings()
    {
        QObject *rootObject = parent->rootObjects().first();
        if (rootObject) {
            QMetaObject::invokeMethod(rootObject, "reopen", Q_ARG(QVariant, "settings"));
        }
        else
        {
            loadMainModule();
        }
    }

    void sendHandshake() {
        LOG_INFO("Connected to device, sending initial packets");
        writePacketToSocket(AirPodsPackets::Connection::HANDSHAKE, "Handshake packet written: ");
    }

    void bluezDeviceConnected(const QString &address, const QString &name)
    {
        QBluetoothDeviceInfo device(QBluetoothAddress(address), name, 0);
        connectToDevice(device);
    }

    void onDeviceDisconnected(const QBluetoothAddress &address)
    {
        LOG_INFO("Device disconnected: " << address.toString());
        if (socket)
        {
            LOG_WARN("Socket is still open, closing it");
            socket->close();
            socket = nullptr;
        }
        if (phoneSocket && phoneSocket->isOpen())
        {
            phoneSocket->write(AirPodsPackets::Connection::AIRPODS_DISCONNECTED);
            LOG_DEBUG("AIRPODS_DISCONNECTED packet written: " << AirPodsPackets::Connection::AIRPODS_DISCONNECTED.toHex());
        }

        // Clear the device name and model
        m_deviceInfo->reset();
        m_bleManager->startScan();
        emit airPodsStatusChanged();

        // Show system notification
        trayManager->showNotification(
            tr("AirPods Disconnected"),
            tr("Your AirPods have been disconnected"));
        trayManager->resetTrayIcon();
    }

    void bluezDeviceDisconnected(const QString &address, const QString &name)
    {
        if (address == m_deviceInfo->bluetoothAddress())
        {
            onDeviceDisconnected(QBluetoothAddress(address));
        } else {
            LOG_WARN("Disconnected device does not match connected device: " << address << " != " << m_deviceInfo->bluetoothAddress());
        }
    }

    void parseMetadata(const QByteArray &data)
    {
        // Verify the data starts with the METADATA header
        if (!data.startsWith(AirPodsPackets::Parse::METADATA))
        {
            LOG_ERROR("Invalid metadata packet: Incorrect header");
            return;
        }

        int pos = AirPodsPackets::Parse::METADATA.size(); // Start after the header

        // Check if there is enough data to skip the initial bytes (based on example structure)
        if (data.size() < pos + 6)
        {
            LOG_ERROR("Metadata packet too short to parse initial bytes");
            return;
        }
        pos += 6; // Skip 6 bytes after the header as per example structure

        auto extractString = [&data, &pos]() -> QString
        {
            if (pos >= data.size())
            {
                return QString();
            }
            int start = pos;
            while (pos < data.size() && data.at(pos) != '\0')
            {
                ++pos;
            }
            QString str = QString::fromUtf8(data.mid(start, pos - start));
            if (pos < data.size())
            {
                ++pos; // Move past the null terminator
            }
            return str;
        };

        m_deviceInfo->setDeviceName(extractString());
        m_deviceInfo->setModelNumber(extractString());
        m_deviceInfo->setManufacturer(extractString());

        m_deviceInfo->setModel(parseModelNumber(m_deviceInfo->modelNumber()));
        emit modelChanged();

        // Log extracted metadata
        LOG_INFO("Parsed AirPods metadata:");
        LOG_INFO("Device Name: " << m_deviceInfo->deviceName());
        LOG_INFO("Model Number: " << m_deviceInfo->modelNumber());
        LOG_INFO("Manufacturer: " << m_deviceInfo->manufacturer());
    }

    QString getEarStatus(char value)
    {
        return (value == 0x00) ? "In Ear" : (value == 0x01) ? "Out of Ear"
                                                            : "In case";
    }

    void connectToDevice(const QBluetoothDeviceInfo &device)
    {
        if (socket && socket->isOpen() && socket->peerAddress() == device.address())
        {
            LOG_INFO("Already connected to the device: " << device.name());
            return;
        }

        LOG_INFO("Connecting to device: " << device.name());

        // Clean up any existing socket
        if (socket)
        {
            socket->close();
            socket->deleteLater();
            socket = nullptr;
        }

        QBluetoothSocket *localSocket = new QBluetoothSocket(QBluetoothServiceInfo::L2capProtocol);
        socket = localSocket;

        // Connection handler
        auto handleConnection = [this, localSocket]()
        {
            connect(localSocket, &QBluetoothSocket::readyRead, this, [this, localSocket]()
                    {
            QByteArray data = localSocket->readAll();
            QMetaObject::invokeMethod(this, "parseData", Qt::QueuedConnection, Q_ARG(QByteArray, data));
            QMetaObject::invokeMethod(this, "relayPacketToPhone", Qt::QueuedConnection, Q_ARG(QByteArray, data)); });
            sendHandshake();
        };

        // Error handler with retry
        auto handleError = [this, device, localSocket](QBluetoothSocket::SocketError error)
        {
            LOG_ERROR("Socket error: " << error << ", " << localSocket->errorString());

            static int retryCount = 0;
            if (retryCount < m_retryAttempts)
            {
                retryCount++;
                LOG_INFO("Retrying connection (attempt " << retryCount << ")");
                QTimer::singleShot(1500, this, [this, device]()
                                   { connectToDevice(device); });
            }
            else
            {
                LOG_ERROR("Failed to connect after 3 attempts");
                retryCount = 0;
            }
        };

        connect(localSocket, &QBluetoothSocket::connected, this, handleConnection);
        connect(localSocket, QOverload<QBluetoothSocket::SocketError>::of(&QBluetoothSocket::errorOccurred),
                this, handleError);

        localSocket->connectToService(device.address(), QBluetoothUuid("74ec2172-0bad-4d01-8f77-997b2be0722a"));
        m_deviceInfo->setBluetoothAddress(device.address().toString());
        notifyAndroidDevice();
    }

    void parseData(const QByteArray &data)
    {
        LOG_DEBUG("Received: " << data.toHex());

        if (data.startsWith(AirPodsPackets::Parse::HANDSHAKE_ACK))
        {
            writePacketToSocket(AirPodsPackets::Connection::SET_SPECIFIC_FEATURES, "Set specific features packet written: ");
        }
        else if (data.startsWith(AirPodsPackets::Parse::FEATURES_ACK))
        {
            writePacketToSocket(AirPodsPackets::Connection::REQUEST_NOTIFICATIONS, "Request notifications packet written: ");
            
            QTimer::singleShot(2000, this, [this]() {
                if (m_deviceInfo->batteryStatus().isEmpty()) {
                    writePacketToSocket(AirPodsPackets::Connection::REQUEST_NOTIFICATIONS, "Request notifications packet written: ");
                }
            });
        }
        // Magic Cloud Keys Response
        else if (data.startsWith(AirPodsPackets::MagicPairing::MAGIC_CLOUD_KEYS_HEADER))
        {
            auto keys = AirPodsPackets::MagicPairing::parseMagicCloudKeysPacket(data);
            LOG_INFO("Received Magic Cloud Keys:");
            LOG_INFO("MagicAccIRK: " << keys.magicAccIRK.toHex());
            LOG_INFO("MagicAccEncKey: " << keys.magicAccEncKey.toHex());

            // Store the keys
            m_deviceInfo->setMagicAccIRK(keys.magicAccIRK);
            m_deviceInfo->setMagicAccEncKey(keys.magicAccEncKey);
            m_deviceInfo->saveToSettings(*m_settings);
        }
        // Get CA state
        else if (data.startsWith(AirPodsPackets::ConversationalAwareness::HEADER)) {
            if (auto result = AirPodsPackets::ConversationalAwareness::parseState(data))
            {
                m_deviceInfo->setConversationalAwareness(result.value());
                LOG_INFO("Conversational awareness state received: " << m_deviceInfo->conversationalAwareness());
            }
        }
        // Noise Control Mode
        else if (data.size() == 11 && data.startsWith(AirPodsPackets::NoiseControl::HEADER))
        {
            if (auto value = AirPodsPackets::NoiseControl::parseMode(data))
            {
                m_deviceInfo->setNoiseControlMode(value.value());
                LOG_INFO("Noise control mode received: " << m_deviceInfo->noiseControlMode());
            }
        }
        // Ear Detection
        else if (data.size() == 8 && data.startsWith(AirPodsPackets::Parse::EAR_DETECTION))
        {
            char primary = data[6];
            char secondary = data[7];
            m_deviceInfo->setPrimaryInEar(data[6] == 0x00);
            m_deviceInfo->setSecondaryInEar(data[7] == 0x00);
            m_deviceInfo->setEarDetectionStatus(QString("Primary: %1, Secondary: %2")
                                                         .arg(getEarStatus(primary), getEarStatus(secondary)));
            LOG_INFO("Ear detection status: " << m_deviceInfo->earDetectionStatus());
        }
        // Battery Status
        else if (data.size() == 22 && data.startsWith(AirPodsPackets::Parse::BATTERY_STATUS))
        {
            m_deviceInfo->getBattery()->parsePacket(data);

            int leftLevel = m_deviceInfo->getBattery()->getState(Battery::Component::Left).level;
            int rightLevel = m_deviceInfo->getBattery()->getState(Battery::Component::Right).level;
            int caseLevel = m_deviceInfo->getBattery()->getState(Battery::Component::Case).level;
            m_deviceInfo->setBatteryStatus(QString("Left: %1%, Right: %2%, Case: %3%")
                                               .arg(leftLevel)
                                               .arg(rightLevel)
                                               .arg(caseLevel));
            LOG_INFO("Battery status: " << m_deviceInfo->batteryStatus());
        }
        // Conversational Awareness Data
        else if (data.size() == 10 && data.startsWith(AirPodsPackets::ConversationalAwareness::DATA_HEADER))
        {
            LOG_INFO("Received conversational awareness data");
            mediaController->handleConversationalAwareness(data);
        }
        else if (data.startsWith(AirPodsPackets::Parse::METADATA))
        {
            parseMetadata(data);
            initiateMagicPairing();
            mediaController->setConnectedDeviceMacAddress(m_deviceInfo->bluetoothAddress().replace(":", "_"));
            if (m_deviceInfo->oneOrMorePodsInEar()) // AirPods get added as output device only after this
            {
                mediaController->activateA2dpProfile();
            }
            m_bleManager->stopScan();
            emit airPodsStatusChanged();
        }
        else if (data.startsWith(AirPodsPackets::OneBudANCMode::HEADER)) {
            if (auto value = AirPodsPackets::OneBudANCMode::parseState(data))
            {
                m_deviceInfo->setOneBudANCMode(value.value());
                LOG_INFO("One Bud ANC mode received: " << m_deviceInfo->oneBudANCMode());
            }
        }
        else
        {
            LOG_DEBUG("Unrecognized packet format: " << data.toHex());
        }
    }

    void connectToPhone() {
        if (!CrossDevice.isEnabled) {
            return;
        }

        if (phoneSocket && phoneSocket->isOpen()) {
            LOG_INFO("Already connected to the phone");
            return;
        }
        QBluetoothAddress phoneAddress("00:00:00:00:00:00"); // Default address, will be overwritten if PHONE_MAC_ADDRESS is set
        QProcessEnvironment env = QProcessEnvironment::systemEnvironment();
        if (!env.value("PHONE_MAC_ADDRESS").isEmpty())
        {
            phoneAddress = QBluetoothAddress(env.value("PHONE_MAC_ADDRESS"));
        }
        phoneSocket = new QBluetoothSocket(QBluetoothServiceInfo::L2capProtocol);
        connect(phoneSocket, &QBluetoothSocket::connected, this, [this]() {
            LOG_INFO("Connected to phone");
            if (!lastBatteryStatus.isEmpty()) {
                phoneSocket->write(lastBatteryStatus);
                LOG_DEBUG("Sent last battery status to phone: " << lastBatteryStatus.toHex());
            }
            if (!lastEarDetectionStatus.isEmpty()) {
                phoneSocket->write(lastEarDetectionStatus);
                LOG_DEBUG("Sent last ear detection status to phone: " << lastEarDetectionStatus.toHex());
            }
        });

        connect(phoneSocket, QOverload<QBluetoothSocket::SocketError>::of(&QBluetoothSocket::errorOccurred), this, [this](QBluetoothSocket::SocketError error) {
            LOG_ERROR("Phone socket error: " << error << ", " << phoneSocket->errorString());
        });

        phoneSocket->connectToService(phoneAddress, QBluetoothUuid("1abbb9a4-10e4-4000-a75c-8953c5471342"));
    }

    void relayPacketToPhone(const QByteArray &packet)
    {
        if (!CrossDevice.isEnabled) {
            return;
        }
        if (phoneSocket && phoneSocket->isOpen())
        {
            phoneSocket->write(AirPodsPackets::Phone::NOTIFICATION + packet);
        }
        else
        {
            connectToPhone();
            LOG_WARN("Phone socket is not open, cannot relay packet");
        }
    }

    void handlePhonePacket(const QByteArray &packet) {
        if (packet.startsWith(AirPodsPackets::Phone::NOTIFICATION))
        {
            QByteArray airpodsPacket = packet.mid(4);
            if (socket && socket->isOpen()) {
                socket->write(airpodsPacket);
                LOG_DEBUG("Relayed packet to AirPods: " << airpodsPacket.toHex());
            } else {
                LOG_ERROR("Socket is not open, cannot relay packet to AirPods");
            }
        }
        else if (packet.startsWith(AirPodsPackets::Phone::CONNECTED))
        {
            LOG_INFO("AirPods connected");
            isConnectedLocally = true;
            CrossDevice.isAvailable = false;
        }
        else if (packet.startsWith(AirPodsPackets::Phone::DISCONNECTED))
        {
            LOG_INFO("AirPods disconnected");
            isConnectedLocally = false;
            CrossDevice.isAvailable = true;
        }
        else if (packet.startsWith(AirPodsPackets::Phone::STATUS_REQUEST))
        {
            LOG_INFO("Connection status request received");
            QByteArray response = (socket && socket->isOpen()) ? AirPodsPackets::Phone::CONNECTED
                                                               : AirPodsPackets::Phone::DISCONNECTED;
            phoneSocket->write(response);
            LOG_DEBUG("Sent connection status response: " << response.toHex());
        }
        else if (packet.startsWith(AirPodsPackets::Phone::DISCONNECT_REQUEST))
        {
            LOG_INFO("Disconnect request received");
            if (socket && socket->isOpen()) {
                socket->close();
                LOG_INFO("Disconnected from AirPods");
                QProcess process;
                process.start("bluetoothctl", QStringList() << "disconnect" << m_deviceInfo->bluetoothAddress());
                process.waitForFinished();
                QString output = process.readAllStandardOutput().trimmed();
                LOG_INFO("Bluetoothctl output: " << output);
                isConnectedLocally = false;
                CrossDevice.isAvailable = true;
            }
        }
        else
        {
            if (socket && socket->isOpen()) {
                socket->write(packet);
                LOG_DEBUG("Relayed packet to AirPods: " << packet.toHex());
            } else {
                LOG_ERROR("Socket is not open, cannot relay packet to AirPods");
            }
        }
    }

    void onPhoneDataReceived() {
        QByteArray data = phoneSocket->readAll();
        LOG_DEBUG("Data received from phone: " << data.toHex());
        QMetaObject::invokeMethod(this, "handlePhonePacket", Qt::QueuedConnection, Q_ARG(QByteArray, data));
    }

    void bleDeviceFound(const BleInfo &device)
    {
        if (BLEUtils::isValidIrkRpa(m_deviceInfo->magicAccIRK(), device.address)) {
            m_deviceInfo->setModel(device.modelName);
            auto decryptet = BLEUtils::decryptLastBytes(device.encryptedPayload, m_deviceInfo->magicAccEncKey());
            m_deviceInfo->getBattery()->parseEncryptedPacket(decryptet, device.primaryLeft);
            m_deviceInfo->setPrimaryInEar(device.isPrimaryInEar);
            m_deviceInfo->setSecondaryInEar(device.isSecondaryInEar);
        }
    }

public:
    void handleMediaStateChange(MediaController::MediaState state) {
        if (state == MediaController::MediaState::Playing) {
            LOG_INFO("Media started playing, sending disconnect request to Android and taking over audio");
            sendDisconnectRequestToAndroid();
            connectToAirPods(true);
        }
    }

    void sendDisconnectRequestToAndroid()
    {
        if (!CrossDevice.isEnabled) return;

        if (phoneSocket && phoneSocket->isOpen())
        {
            phoneSocket->write(AirPodsPackets::Phone::DISCONNECT_REQUEST);
            LOG_DEBUG("Sent disconnect request to Android: " << AirPodsPackets::Phone::DISCONNECT_REQUEST.toHex());
        }
        else
        {
            LOG_WARN("Phone socket is not open, cannot send disconnect request");
        }
    }

    bool isPhoneConnected() {
        return phoneSocket && phoneSocket->isOpen();
    }

    void connectToAirPods(bool force) {
        if (socket && socket->isOpen()) {
            LOG_INFO("Already connected to AirPods");
            return;
        }

        if (force) {
            LOG_INFO("Forcing connection to AirPods");
            QProcess process;
            process.start("bluetoothctl", QStringList() << "connect" << m_deviceInfo->bluetoothAddress());
            process.waitForFinished();
            QString output = process.readAllStandardOutput().trimmed();
            LOG_INFO("Bluetoothctl output: " << output);
            if (output.contains("Connection successful")) {
                LOG_INFO("Connection successful, proceeding with L2CAP connection");
                QBluetoothAddress btAddress(m_deviceInfo->bluetoothAddress());
                forceL2capConnection(btAddress);
            } else {
                LOG_ERROR("Connection failed, cannot proceed with L2CAP connection");
            }
        }
        QBluetoothLocalDevice localDevice;
        const QList<QBluetoothAddress> connectedDevices = localDevice.connectedDevices();
        for (const QBluetoothAddress &address : connectedDevices) {
            QBluetoothDeviceInfo device(address, "", 0);
            LOG_DEBUG("Connected device: " << device.name() << " (" << device.address().toString() << ")");
            if (isAirPodsDevice(device)) {
                connectToDevice(device);
                return;
            }
        }
        LOG_WARN("AirPods not found among connected devices");
    }

    void forceL2capConnection(const QBluetoothAddress &address) {
        LOG_INFO("Retrying L2CAP connection for up to 10 seconds...");
        QBluetoothDeviceInfo device(address, "", 0);
        QElapsedTimer timer;
        timer.start();
        while (timer.elapsed() < 10000) {
            QProcess bcProcess;
            bcProcess.start("bluetoothctl", QStringList() << "connect" << address.toString());
            bcProcess.waitForFinished();
            QString output = bcProcess.readAllStandardOutput().trimmed();
            LOG_INFO("Bluetoothctl output: " << output);
            if (output.contains("Connection successful")) {
                connectToDevice(device);
                QThread::sleep(1);
                if (socket && socket->isOpen()) {
                    LOG_INFO("Successfully connected to device: " << address.toString());
                    return;
                }
            } else {
                LOG_WARN("Connection attempt failed, retrying...");
            }
        }
        LOG_ERROR("Failed to connect to device within 10 seconds: " << address.toString());
    }

    void initializeBluetooth() {
        connectToPhone();

        m_deviceInfo->loadFromSettings(*m_settings);
        if (!areAirpodsConnected()) {
            m_bleManager->startScan();
        }
    }

    void loadMainModule() {
        parent->load(QUrl(QStringLiteral("qrc:/linux/Main.qml")));
    }

signals:
    void noiseControlModeChanged(NoiseControlMode mode);
    void earDetectionStatusChanged(const QString &status);
    void batteryStatusChanged(const QString &status);
    void conversationalAwarenessChanged(bool enabled);
    void adaptiveNoiseLevelChanged(int level);
    void deviceNameChanged(const QString &name);
    void modelChanged();
    void primaryChanged();
    void airPodsStatusChanged();
    void earDetectionBehaviorChanged(int behavior);
    void crossDeviceEnabledChanged(bool enabled);
    void notificationsEnabledChanged(bool enabled);
    void retryAttemptsChanged(int attempts);
    void oneBudANCModeChanged(bool enabled);

private:
    QBluetoothSocket *socket = nullptr;
    QBluetoothSocket *phoneSocket = nullptr;
    QByteArray lastBatteryStatus;
    QByteArray lastEarDetectionStatus;
    MediaController* mediaController;
    TrayIconManager *trayManager;
    BluetoothMonitor *monitor;
    QSettings *m_settings;
    AutoStartManager *m_autoStartManager;
    int m_retryAttempts = 3;
    bool m_hideOnStart = false;
    DeviceInfo *m_deviceInfo;
    BleManager *m_bleManager;
};

int main(int argc, char *argv[]) {
    QApplication app(argc, argv);

    QSharedMemory sharedMemory;
    sharedMemory.setKey("TcpServer-Key");

    // Check if app is already open
    if(sharedMemory.create(1) == false) 
    {
        LOG_INFO("Another instance already running! Opening App Window Instead");
        QLocalSocket socket;
        // Connect to the original app, then trigger the reopen signal
        socket.connectToServer("app_server");
        if (socket.waitForConnected(500)) {
            socket.write("reopen");
            socket.flush();
            socket.waitForBytesWritten(500);
            socket.disconnectFromServer();
            app.exit(); // exit; process already running
            return 0;
        }
        else
        {
            // Failed connection, log and open the app (assume it's not running)
            LOG_ERROR("Failed to connect to the original app instance. Assuming it is not running.");
            LOG_DEBUG("Socket error: " << socket.errorString());
        }
    }
    app.setQuitOnLastWindowClosed(false);

    bool debugMode = false;
    bool hideOnStart = false;
    for (int i = 1; i < argc; ++i) {
        if (QString(argv[i]) == "--debug")
            debugMode = true;

        if (QString(argv[i]) == "--hide")
            hideOnStart = true;
    }

    QQmlApplicationEngine engine;
    qmlRegisterType<Battery>("me.kavishdevar.Battery", 1, 0, "Battery");
    qmlRegisterType<DeviceInfo>("me.kavishdevar.DeviceInfo", 1, 0, "DeviceInfo");
    AirPodsTrayApp *trayApp = new AirPodsTrayApp(debugMode, hideOnStart, &engine);
    engine.rootContext()->setContextProperty("airPodsTrayApp", trayApp);
    engine.addImageProvider("qrcode", new QRCodeImageProvider());
    trayApp->loadMainModule();

    QLocalServer server;
    QLocalServer::removeServer("app_server");

    if (!server.listen("app_server"))
    {
        LOG_ERROR("Unable to start the listening server");
        LOG_DEBUG("Server error: " << server.errorString());
    }
    else
    {
        LOG_DEBUG("Server started, waiting for connections...");
    }
    QObject::connect(&server, &QLocalServer::newConnection, [&]() {
        QLocalSocket* socket = server.nextPendingConnection();
        // Handles Proper Connection
        QObject::connect(socket, &QLocalSocket::readyRead, [socket, &engine, &trayApp]() {
            QString msg = socket->readAll();
            // Check if the message is "reopen", if so, trigger onOpenApp function
            if (msg == "reopen") {
                LOG_INFO("Reopening app window");
                QObject *rootObject = engine.rootObjects().first();
                if (rootObject) {
                    QMetaObject::invokeMethod(rootObject, "reopen", Q_ARG(QVariant, "app"));
                }
                else
                {
                    trayApp->loadMainModule();
                }
            }
            else
            {
                LOG_ERROR("Unknown message received: " << msg);
            }
            socket->disconnectFromServer();
        });
        // Handles connection errors
        QObject::connect(socket, &QLocalSocket::errorOccurred, [socket]() {
            LOG_ERROR("Failed to connect to the duplicate app instance");
            LOG_DEBUG("Connection error: " << socket->errorString());
        });
        
        // Handle server-level errors
        QObject::connect(&server, &QLocalServer::serverError, [&]() {
            LOG_ERROR("Server failed to accept a new connection");
            LOG_DEBUG("Server error: " << server.errorString());
        });
    });

    QObject::connect(&app, &QCoreApplication::aboutToQuit, [&]() {
        LOG_DEBUG("Application is about to quit. Cleaning up...");
        sharedMemory.detach();
    });
    return app.exec();
}

#include "main.moc"

#include "mediacontroller.h"
#include "logger.h"

#include <QDebug>
#include <QProcess>
#include <QRegularExpression>
#include <QDBusConnection>
#include <QDBusConnectionInterface>

MediaController::MediaController(QObject *parent) : QObject(parent) {
  // No additional initialization required here
}

void MediaController::handleEarDetection(const QString &status)
{
  if (earDetectionBehavior == Disabled)
  {
    LOG_DEBUG("Ear detection is disabled, ignoring status");
    return;
  }

  bool primaryInEar = false;
  bool secondaryInEar = false;

  QStringList parts = status.split(", ");
  if (parts.size() == 2)
  {
    primaryInEar = parts[0].contains("In Ear");
    secondaryInEar = parts[1].contains("In Ear");
  }

  LOG_DEBUG("Ear detection status: primaryInEar="
            << primaryInEar << ", secondaryInEar=" << secondaryInEar
            << ", isAirPodsActive=" << isActiveOutputDeviceAirPods());

  // First handle playback pausing based on selected behavior
  bool shouldPause = false;
  bool shouldResume = false;

  if (earDetectionBehavior == PauseWhenOneRemoved)
  {
    shouldPause = !primaryInEar || !secondaryInEar;
    shouldResume = primaryInEar && secondaryInEar;
  }
  else if (earDetectionBehavior == PauseWhenBothRemoved)
  {
    shouldPause = !primaryInEar && !secondaryInEar;
    shouldResume = primaryInEar || secondaryInEar;
  }

  // Handle playback state changes
  if (shouldPause && isActiveOutputDeviceAirPods())
  {
    MediaState currentState = getCurrentMediaState();
    LOG_DEBUG("Playback status: " << currentState);
    if (currentState == MediaState::Playing)
    {
      pause();
    }
  }

  // Handle device profile switching
  if (primaryInEar || secondaryInEar)
  {
    LOG_INFO("At least one AirPod is in ear");
    activateA2dpProfile();

    if (shouldResume && wasPausedByApp && isActiveOutputDeviceAirPods())
    {
      play();
    }
  }
  else
  {
    LOG_INFO("Both AirPods are out of ear");
    removeAudioOutputDevice();
  }
}

void MediaController::setEarDetectionBehavior(EarDetectionBehavior behavior)
{
  earDetectionBehavior = behavior;
  LOG_INFO("Set ear detection behavior to: " << behavior);
}

void MediaController::followMediaChanges() {
  playerctlProcess = new QProcess(this);
  connect(playerctlProcess, &QProcess::readyReadStandardOutput, this,
          [this]() {
            QString output =
                playerctlProcess->readAllStandardOutput().trimmed();
            LOG_DEBUG("Playerctl output: " << output);
            m_mediaState = mediaStateFromPlayerctlOutput(output);
            emit mediaStateChanged(m_mediaState);
          });
  playerctlProcess->start("playerctl", QStringList() << "--follow" << "status");
}

bool MediaController::isActiveOutputDeviceAirPods() {
  QProcess process;
  process.start("pactl", QStringList() << "get-default-sink");
  process.waitForFinished();
  QString output = process.readAllStandardOutput().trimmed();
  LOG_DEBUG("Default sink: " << output);
  return output.contains(connectedDeviceMacAddress);
}

void MediaController::handleConversationalAwareness(const QByteArray &data) {
  LOG_DEBUG("Handling conversational awareness data: " << data.toHex());
  bool lowered = data[9] == 0x01;
  LOG_INFO("Conversational awareness: " << (lowered ? "enabled" : "disabled"));

  if (lowered) {
    if (initialVolume == -1 && isActiveOutputDeviceAirPods()) {
      QProcess process;
      process.start("pactl", QStringList()
                                 << "get-sink-volume" << "@DEFAULT_SINK@");
      process.waitForFinished();
      QString output = process.readAllStandardOutput();
      QRegularExpression re("front-left: \\d+ /\\s*(\\d+)%");
      QRegularExpressionMatch match = re.match(output);
      if (match.hasMatch()) {
        LOG_DEBUG("Matched: " << match.captured(1));
        initialVolume = match.captured(1).toInt();
      } else {
        LOG_ERROR("Failed to parse initial volume from output: " << output);
        return;
      }
    }
    QProcess::execute(
        "pactl", QStringList() << "set-sink-volume" << "@DEFAULT_SINK@"
                               << QString::number(initialVolume * 0.20) + "%");
    LOG_INFO("Volume lowered to 0.20 of initial which is "
             << initialVolume * 0.20 << "%");
  } else {
    if (initialVolume != -1 && isActiveOutputDeviceAirPods()) {
      QProcess::execute("pactl", QStringList()
                                     << "set-sink-volume" << "@DEFAULT_SINK@"
                                     << QString::number(initialVolume) + "%");
      LOG_INFO("Volume restored to " << initialVolume << "%");
      initialVolume = -1;
    }
  }
}

void MediaController::activateA2dpProfile() {
  if (connectedDeviceMacAddress.isEmpty() || m_deviceOutputName.isEmpty()) {
    LOG_WARN("Connected device MAC address or output name is empty, cannot activate A2DP profile");
    return;
  }

  LOG_INFO("Activating A2DP profile for AirPods");
  int result = QProcess::execute(
      "pactl", QStringList()
                   << "set-card-profile"
                   << m_deviceOutputName << "a2dp-sink");
  if (result != 0) {
    LOG_ERROR("Failed to activate A2DP profile");
  }
}

void MediaController::removeAudioOutputDevice() {
  if (connectedDeviceMacAddress.isEmpty() || m_deviceOutputName.isEmpty()) {
    LOG_WARN("Connected device MAC address or output name is empty, cannot remove audio output device");
    return;
  }
  
  LOG_INFO("Removing AirPods as audio output device");
  int result = QProcess::execute(
      "pactl", QStringList()
                   << "set-card-profile"
                   << m_deviceOutputName << "off");
  if (result != 0) {
    LOG_ERROR("Failed to remove AirPods as audio output device");
  }
}

void MediaController::setConnectedDeviceMacAddress(const QString &macAddress) {
  connectedDeviceMacAddress = macAddress;
  m_deviceOutputName = getAudioDeviceName();
  LOG_INFO("Device output name set to: " << m_deviceOutputName);
}

MediaController::MediaState MediaController::mediaStateFromPlayerctlOutput(
    const QString &output) {
  if (output == "Playing") {
    return MediaState::Playing;
  } else if (output == "Paused") {
    return MediaState::Paused;
  } else {
    return MediaState::Stopped;
  }
}

bool MediaController::sendMediaPlayerCommand(const QString &method)
{
  QDBusInterface *iface = getMediaPlayerInterface();
  if (!iface)
  {
    LOG_ERROR("No media player interface available for " << method);
    return false;
  }

  // Use QDBusMessage for more control and error handling
  QDBusMessage message = QDBusMessage::createMethodCall(
      iface->service(),
      iface->path(),
      iface->interface(),
      method);

  QDBusPendingCall call = iface->connection().asyncCall(message);
  call.waitForFinished();

  if (call.isError())
  {
    LOG_ERROR("Failed to execute " << method << ": " << call.error().message());
    delete iface;
    return false;
  }

  delete iface;
  return true;
}

void MediaController::pause()
{
  if (sendMediaPlayerCommand("Pause"))
  {
    LOG_INFO("Playback paused via DBus");
    wasPausedByApp = true;
  }
}

void MediaController::play()
{
  if (sendMediaPlayerCommand("Play"))
  {
    LOG_INFO("Playback resumed via DBus");
    wasPausedByApp = false;
  }
}

MediaController::~MediaController() {
  if (playerctlProcess) {
    playerctlProcess->terminate();
    if (!playerctlProcess->waitForFinished()) {
      playerctlProcess->kill();
      playerctlProcess->waitForFinished(1000);
    }
  }
}

QString MediaController::getAudioDeviceName()
{
  if (connectedDeviceMacAddress.isEmpty()) { return QString(); }

  // Set up QProcess to run pactl directly
  QProcess process;
  process.start("pactl", QStringList() << "list" << "cards" << "short");
  if (!process.waitForFinished(3000)) // Timeout after 3 seconds
  {
    LOG_ERROR("pactl command failed or timed out: " << process.errorString());
    return QString();
  }

  // Check for execution errors
  if (process.exitCode() != 0)
  {
    LOG_ERROR("pactl exited with error code: " << process.exitCode());
    return QString();
  }

  // Read and parse the command output
  QString output = process.readAllStandardOutput();
  QStringList lines = output.split("\n", Qt::SkipEmptyParts);

  // Iterate through each line to find a matching Bluetooth sink
  for (const QString &line : lines)
  {
    QStringList fields = line.split("\t", Qt::SkipEmptyParts);
    if (fields.size() < 2) { continue; }

    QString sinkName = fields[1].trimmed();
    if (sinkName.startsWith("bluez") && sinkName.contains(connectedDeviceMacAddress))
    {
      return sinkName;
    }
  }

  // No matching sink found
  LOG_ERROR("No matching Bluetooth sink found for MAC address: " << connectedDeviceMacAddress);
  return QString();
}

QDBusInterface *MediaController::getMediaPlayerInterface()
{
  // List all media player services
  QDBusConnection sessionBus = QDBusConnection::sessionBus();
  QDBusInterface dbusInterface("org.freedesktop.DBus", "/org/freedesktop/DBus",
                               "org.freedesktop.DBus", sessionBus);
  QDBusReply<QStringList> reply = dbusInterface.call("ListNames");

  if (!reply.isValid())
  {
    LOG_ERROR("Failed to list DBus services: " << reply.error().message());
    return nullptr;
  }

  QStringList services = reply.value();
  QString mediaPlayerService;

  for (const QString &service : services)
  {
    if (service.startsWith("org.mpris.MediaPlayer2."))
    {
      mediaPlayerService = service;
      break;
    }
  }

  if (mediaPlayerService.isEmpty())
  {
    LOG_DEBUG("No active media player found on DBus");
    return nullptr;
  }

  LOG_DEBUG("Found media player service: " << mediaPlayerService);
  return new QDBusInterface(mediaPlayerService, "/org/mpris/MediaPlayer2",
                            "org.mpris.MediaPlayer2.Player", sessionBus, this);
}
#ifndef MEDIACONTROLLER_H
#define MEDIACONTROLLER_H

#include <QDBusInterface>
#include <QDBusConnection>
#include <QDBusReply>
#include <QObject>

class QProcess;

class MediaController : public QObject
{
  Q_OBJECT
public:
  enum MediaState
  {
    Playing,
    Paused,
    Stopped
  };
  Q_ENUM(MediaState)
  enum EarDetectionBehavior
  {
    PauseWhenOneRemoved,
    PauseWhenBothRemoved,
    Disabled
  };
  Q_ENUM(EarDetectionBehavior)

  explicit MediaController(QObject *parent = nullptr);
  ~MediaController();

  void handleEarDetection(const QString &status);
  void followMediaChanges();
  bool isActiveOutputDeviceAirPods();
  void handleConversationalAwareness(const QByteArray &data);
  void activateA2dpProfile();
  void removeAudioOutputDevice();
  void setConnectedDeviceMacAddress(const QString &macAddress);

  void setEarDetectionBehavior(EarDetectionBehavior behavior);
  inline EarDetectionBehavior getEarDetectionBehavior() const { return earDetectionBehavior; }

  void pause();
  void play();

Q_SIGNALS:
  void mediaStateChanged(MediaState state);

private:
  bool sendMediaPlayerCommand(const QString &method);
  QDBusInterface *getMediaPlayerInterface();
  MediaState getCurrentMediaState() const { return m_mediaState; };
  MediaState mediaStateFromPlayerctlOutput(const QString &output);
  QString getAudioDeviceName();

  QProcess *playerctlProcess = nullptr;
  bool wasPausedByApp = false;
  int initialVolume = -1;
  QString connectedDeviceMacAddress;
  EarDetectionBehavior earDetectionBehavior = PauseWhenOneRemoved;
  QString m_deviceOutputName;
  MediaState m_mediaState = Stopped;
};

#endif // MEDIACONTROLLER_H
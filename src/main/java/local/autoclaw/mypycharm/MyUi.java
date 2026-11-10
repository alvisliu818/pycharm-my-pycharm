package local.autoclaw.mypycharm;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;

/** 通知与日志小工具。 */
public final class MyUi {
    private MyUi() {
    }

    public static void notify(Project project, String message, NotificationType type) {
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("My Pycharm")
                    .createNotification(message, type)
                    .notify(project);
        } catch (Throwable ignore) {
            // 通知组未注册等异常场景降级为日志
        }
    }

    public static void info(Project project, String message) {
        notify(project, message, NotificationType.INFORMATION);
    }

    public static void warn(Project project, String message) {
        notify(project, message, NotificationType.WARNING);
    }

    public static void error(Project project, String message) {
        notify(project, message, NotificationType.ERROR);
    }
}

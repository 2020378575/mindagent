package com.mindbridge.agent.service.mcp;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.AlertRecord;
import com.mindbridge.agent.domain.OpsArchiveRecord;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * SMTP 邮件预警实现。
 *
 * <p>高优先级归档触发后，把摘要信息发送给配置的研究运维邮箱。</p>
 */
public class SmtpAlertNotifier implements AlertNotifier {

    private final JavaMailSender mailSender;
    private final MindBridgeProperties properties;

    public SmtpAlertNotifier(JavaMailSender mailSender, MindBridgeProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void notify(AlertRecord alertRecord, OpsArchiveRecord report) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getMcp().getEmail().getFrom());
        message.setTo(alertRecord.getRecipient());
        message.setSubject("【高优先级预警】用户 %s 存在高风险信号".formatted(report.getUser().getUsername()));
        message.setText("""
                系统监测到 1 条高优先级归档信号，请及时关注。

                【预警信息如下】
                报告ID：%s
                用户ID：%s
                学生：%s
                对话内容：%s
                情绪判定：%s
                综合情绪得分：%.2f
                优先级：%s
                判断摘要：%s

                """.formatted(
                report.getId(),
                report.getUser().getUsername(),
                report.getUser().getDisplayName(),
                report.getContent(),
                report.getEmotion(),
                report.getEmotionScore(),
                report.getRiskLevel(),
                report.getSummary()));
        mailSender.send(message);
    }
}

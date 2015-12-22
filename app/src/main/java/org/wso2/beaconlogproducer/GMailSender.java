package org.wso2.beaconlogproducer;

import android.os.Environment;
import android.util.Log;

import org.apache.commons.io.filefilter.WildcardFileFilter;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Security;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

public class GMailSender extends javax.mail.Authenticator {
    private String mailhost = "smtp.gmail.com";
    private String user;
    private String password;
    private Session session;

    static {
        Security.addProvider(new JSSEProvider());
    }

    public GMailSender(String user, String password) {
        this.user = user;
        this.password = password;

        Properties props = new Properties();
        props.setProperty("mail.transport.protocol", "smtp");
        props.setProperty("mail.host", mailhost);
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.port", "465");
        props.put("mail.smtp.socketFactory.port", "465");
        props.put("mail.smtp.socketFactory.class",
                "javax.net.ssl.SSLSocketFactory");
        props.put("mail.smtp.socketFactory.fallback", "false");
        props.setProperty("mail.smtp.quitwait", "false");
        session = Session.getDefaultInstance(props, this);
    }

    protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(user, password);
    }

    /**
     * Send email with attachments
     * @param subject
     * @param body
     * @param sender
     * @param recipients
     * @throws Exception
     */
    public void sendMail(String subject, String body, String sender, String recipients) throws Exception {
        try{
            boolean attachmentsAvailable = false;
            String currentlyUsedLogfile = null;
            MimeMessage message = new MimeMessage(session);
            message.setSender(new InternetAddress(sender));
            message.setSubject(subject);

            // attachments
            Multipart multipart = new MimeMultipart();
            File dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath());
            FileFilter fileFilter = new WildcardFileFilter("beaconlog*");
            File[] files = dir.listFiles(fileFilter);
            for (File file : files) {
                SimpleDateFormat format = new SimpleDateFormat("dd-MM-yyyy-HH");
                String fileName = "beaconlog-" + format.format(new Date()) + ".log";
                if(!fileName.equals(file.getName())) {
                    addAttachment(multipart, file.getAbsolutePath());
                    body += "\n" + file.getAbsolutePath();
                    attachmentsAvailable = true;
                } else {
                    currentlyUsedLogfile = file.getName();
                }
            }

            if(attachmentsAvailable) {
                if (recipients.indexOf(',') > 0) {
                    message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipients));
                } else {
                    message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipients));
                }
                // Setup message body
                BodyPart messageBodyPart = new MimeBodyPart();
                messageBodyPart.setText(body);
                multipart.addBodyPart(messageBodyPart);

                // Put parts in message
                message.setContent(multipart);
                Transport.send(message);
                deleteSentFiles(currentlyUsedLogfile);
            }
        } catch (Exception e) {
            Log.e("Error composing mail", e.getMessage());
        }
    }

    /**
     * Delete the files
     * @param currentlyUsedLogfile name of the log files to which the logs are currently written
     */
    private void deleteSentFiles(String currentlyUsedLogfile) {
        File dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath());
        FileFilter fileFilter = new WildcardFileFilter("beaconlog*");
        File[] files = dir.listFiles(fileFilter);
        for (File file : files) {
            if(!currentlyUsedLogfile.equals(file.getName())) {
                boolean isDeleted = file.delete();
                Log.e("Deleted : " + file.getName(), " : " + isDeleted);
            }
        }
    }

    /**
     * Prepare email attachments
     * @param filename file to be added as attachment
     * @throws Exception
     */
    public void addAttachment(Multipart multipart, String filename) throws Exception {
        BodyPart messageBodyPart = new MimeBodyPart();
        DataSource source = new FileDataSource(filename);
        messageBodyPart.setDataHandler(new DataHandler(source));
        messageBodyPart.setFileName(filename);
        multipart.addBodyPart(messageBodyPart);
    }

    public class ByteArrayDataSource implements DataSource {
        private byte[] data;
        private String type;

        public ByteArrayDataSource(byte[] data, String type) {
            super();
            this.data = data;
            this.type = type;
        }

        public ByteArrayDataSource(byte[] data) {
            super();
            this.data = data;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getContentType() {
            if (type == null)
                return "application/octet-stream";
            else
                return type;
        }

        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(data);
        }

        public String getName() {
            return "ByteArrayDataSource";
        }

        public OutputStream getOutputStream() throws IOException {
            throw new IOException("Not Supported");
        }
    }
}

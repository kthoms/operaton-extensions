/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from https://github.com/camunda-community-hub/camunda-platform-7-mail (Apache-2.0)
 */
package org.operaton.extensions.connector.mail.delete;

import jakarta.mail.Flags.Flag;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.search.MessageIDTerm;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.SearchTerm;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.operaton.connect.spi.ConnectorResponse;
import org.operaton.extensions.connector.mail.AbstractMailConnector;
import org.operaton.extensions.connector.mail.EmptyResponse;
import org.operaton.extensions.connector.mail.MailConnectorException;
import org.operaton.extensions.connector.mail.MailInvocation;
import org.operaton.extensions.connector.mail.dto.Mail;
import org.operaton.extensions.connector.mail.service.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The {@code mail-delete} connector: deletes messages from an IMAP folder (flag and expunge). Stateless. */
public class DeleteMailConnector extends AbstractMailConnector<DeleteMailRequest, EmptyResponse> {

    public static final String CONNECTOR_ID = "mail-delete";

    private static final Logger LOG = LoggerFactory.getLogger(DeleteMailConnector.class);

    public DeleteMailConnector() {
        super(CONNECTOR_ID);
    }

    @Override
    public DeleteMailRequest createRequest() {
        return new DeleteMailRequest(this);
    }

    @Override
    public ConnectorResponse execute(DeleteMailRequest request) {
        MailService service = mailService();
        String folderName = request.getFolder();
        try {
            service.withFolder(folderName, true, true, folder -> {
                List<Message> messages = find(folder, request);
                new MailInvocation<List<Message>>(messages, request, interceptors(), target -> {
                            for (Message message : target) {
                                message.setFlag(Flag.DELETED, true);
                            }
                            return null;
                        })
                        .proceed();
                LOG.debug("deleted {} mails from folder '{}'", messages.size(), folderName);
                return null;
            });
        } catch (MailConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new MailConnectorException("Failed to delete mails from '" + folderName + "': " + e.getMessage(), e);
        }
        return new EmptyResponse();
    }

    private List<Message> find(Folder folder, DeleteMailRequest request) throws Exception {
        if (!request.getMails().isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (Mail mail : request.getMails()) {
                if (mail.getMessageId() == null) {
                    throw new MailConnectorException(
                            "mail-delete: a mail without a message id cannot be deleted by 'mails': " + mail);
                }
                ids.add(mail.getMessageId());
            }
            return byIds(folder, ids);
        }
        if (!request.getMessageIds().isEmpty()) {
            return byIds(folder, request.getMessageIds());
        }
        int[] numbers =
                request.getMessageNumbers().stream().mapToInt(Integer::intValue).toArray();
        try {
            return List.of(folder.getMessages(numbers));
        } catch (IndexOutOfBoundsException e) {
            throw new MailConnectorException(
                    "mail-delete: a message number is out of range in folder '" + folder.getName() + "' ("
                            + folder.getMessageCount() + " messages)",
                    e);
        }
    }

    private static List<Message> byIds(Folder folder, List<String> ids) throws Exception {
        SearchTerm term = new OrTerm(ids.stream().map(MessageIDTerm::new).toArray(SearchTerm[]::new));
        List<Message> found = List.of(folder.search(term));
        Set<String> missing = new LinkedHashSet<>(ids);
        for (Message message : found) {
            if (message instanceof jakarta.mail.internet.MimeMessage mime) {
                missing.remove(mime.getMessageID());
            }
        }
        if (!missing.isEmpty()) {
            throw new MailConnectorException(
                    "mail-delete: no message found in folder '" + folder.getName() + "' for message ids " + missing);
        }
        return found;
    }
}

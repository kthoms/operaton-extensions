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
package org.operaton.extensions.connector.mail.poll;

import jakarta.mail.Flags;
import jakarta.mail.Flags.Flag;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.operaton.connect.spi.ConnectorResponse;
import org.operaton.extensions.connector.mail.AbstractMailConnector;
import org.operaton.extensions.connector.mail.MailConnectorException;
import org.operaton.extensions.connector.mail.MailInvocation;
import org.operaton.extensions.connector.mail.dto.Mail;
import org.operaton.extensions.connector.mail.service.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The {@code mail-poll} connector: reads messages from an IMAP folder. Stateless and thread-safe. */
public class PollMailConnector extends AbstractMailConnector<PollMailRequest, PollMailResponse> {

    public static final String CONNECTOR_ID = "mail-poll";

    private static final Logger LOG = LoggerFactory.getLogger(PollMailConnector.class);

    public PollMailConnector() {
        super(CONNECTOR_ID);
    }

    @Override
    public PollMailRequest createRequest() {
        return new PollMailRequest(this);
    }

    @Override
    public ConnectorResponse execute(PollMailRequest request) {
        MailService service = mailService();
        String folderName = request.getFolder();
        boolean markAsRead = request.getMarkAsRead();
        Path downloadDir =
                request.downloadAttachments() ? Paths.get(service.settings().attachmentPath()) : null;
        try {
            List<Mail> mails = service.withFolder(folderName, markAsRead, false, folder -> {
                @SuppressWarnings("unchecked")
                List<Message> messages = (List<Message>)
                        new MailInvocation<Folder>(folder, request, interceptors(), target -> search(target, request))
                                .proceed();
                List<Mail> result = new ArrayList<>();
                for (Message message : messages) {
                    result.add(Mail.from(message, downloadDir, false));
                    if (markAsRead) {
                        message.setFlag(Flag.SEEN, true);
                    }
                }
                return result;
            });
            LOG.debug("polled {} mails from folder '{}'", mails.size(), folderName);
            return new PollMailResponse(mails);
        } catch (MailConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new MailConnectorException("Failed to poll mails from '" + folderName + "': " + e.getMessage(), e);
        }
    }

    /** Messages matching the request's filter, oldest first, limited. */
    protected List<Message> search(Folder folder, PollMailRequest request) throws Exception {
        List<SearchTerm> terms = new ArrayList<>();
        terms.add(new FlagTerm(new Flags(Flag.DELETED), false));
        if (request.getUnreadOnly()) {
            terms.add(new FlagTerm(new Flags(Flag.SEEN), false));
        }
        if (request.getFrom() != null) {
            terms.add(new FromStringTerm(request.getFrom()));
        }
        if (request.getSubject() != null) {
            terms.add(new SubjectTerm(request.getSubject()));
        }
        if (request.getReceivedSince() != null) {
            terms.add(new ReceivedDateTerm(ComparisonTerm.GE, request.getReceivedSince()));
        }
        Message[] found = folder.search(new AndTerm(terms.toArray(SearchTerm[]::new)));
        List<Message> messages = new ArrayList<>(Arrays.asList(found));
        messages.sort(Comparator.comparingInt(Message::getMessageNumber));
        Integer limit = request.getLimit();
        return limit != null && messages.size() > limit ? messages.subList(0, limit) : messages;
    }
}

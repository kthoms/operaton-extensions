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

import java.util.Arrays;
import java.util.List;
import org.operaton.connect.impl.AbstractConnectorRequest;
import org.operaton.connect.spi.Connector;
import org.operaton.extensions.connector.mail.AbstractMailConnector;
import org.operaton.extensions.connector.mail.EmptyResponse;
import org.operaton.extensions.connector.mail.MailConnectorException;
import org.operaton.extensions.connector.mail.MailParameters;
import org.operaton.extensions.connector.mail.dto.Mail;

/**
 * Request of the {@code mail-delete} connector. Input parameters: {@code folder} (default {@code mail.poll.folder}) and
 * exactly one of {@code mails} (the {@link Mail} objects returned by {@code mail-poll}), {@code messageIds} or {@code
 * messageNumbers}. Message numbers are only valid within the folder state they were read from; prefer ids.
 */
public class DeleteMailRequest extends AbstractConnectorRequest<EmptyResponse> {

    protected static final String PARAM_FOLDER = "folder";
    protected static final String PARAM_MAILS = "mails";
    protected static final String PARAM_MESSAGE_IDS = "messageIds";
    protected static final String PARAM_MESSAGE_NUMBERS = "messageNumbers";

    private final AbstractMailConnector<?, ?> mailConnector;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public DeleteMailRequest(AbstractMailConnector<?, ?> connector) {
        super((Connector) connector);
        this.mailConnector = connector;
    }

    public String getFolder() {
        String folder = MailParameters.string(PARAM_FOLDER, getRequestParameter(PARAM_FOLDER));
        return folder != null ? folder : mailConnector.mailService().settings().pollFolder();
    }

    public DeleteMailRequest folder(String folder) {
        setRequestParameter(PARAM_FOLDER, folder);
        return this;
    }

    public List<Mail> getMails() {
        List<Object> items = MailParameters.list(PARAM_MAILS, getRequestParameter(PARAM_MAILS));
        for (Object item : items) {
            if (!(item instanceof Mail)) {
                throw new MailConnectorException(
                        "mail-delete: parameter 'mails' must contain mails returned by " + "mail-poll, but had "
                                + (item == null ? "null" : item.getClass().getName()));
            }
        }
        return items.stream().map(Mail.class::cast).toList();
    }

    public DeleteMailRequest mails(Mail... mails) {
        setRequestParameter(PARAM_MAILS, Arrays.asList(mails));
        return this;
    }

    public List<String> getMessageIds() {
        return MailParameters.list(PARAM_MESSAGE_IDS, getRequestParameter(PARAM_MESSAGE_IDS)).stream()
                .map(String::valueOf)
                .toList();
    }

    public DeleteMailRequest messageIds(String... messageIds) {
        setRequestParameter(PARAM_MESSAGE_IDS, Arrays.asList(messageIds));
        return this;
    }

    public List<Integer> getMessageNumbers() {
        return MailParameters.list(PARAM_MESSAGE_NUMBERS, getRequestParameter(PARAM_MESSAGE_NUMBERS)).stream()
                .map(n -> MailParameters.integer(PARAM_MESSAGE_NUMBERS, n))
                .toList();
    }

    public DeleteMailRequest messageNumbers(Integer... messageNumbers) {
        setRequestParameter(PARAM_MESSAGE_NUMBERS, Arrays.asList(messageNumbers));
        return this;
    }

    @Override
    protected boolean isRequestValid() {
        int given = (getMails().isEmpty() ? 0 : 1)
                + (getMessageIds().isEmpty() ? 0 : 1)
                + (getMessageNumbers().isEmpty() ? 0 : 1);
        if (given != 1) {
            throw new MailConnectorException(
                    "mail-delete: exactly one of the parameters 'mails', 'messageIds' or 'messageNumbers' must be set");
        }
        return true;
    }

    @Override
    public String toString() {
        return "DeleteMailRequest [folder=" + requestParameters.get(PARAM_FOLDER) + "]";
    }
}

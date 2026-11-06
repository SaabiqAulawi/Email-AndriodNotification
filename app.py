"""Forward unread email messages to WhatsApp through Twilio."""

from __future__ import annotations

import base64
import email
import imaplib
import logging
import os
import time
from email.header import decode_header
from email.message import Message
from email.utils import parseaddr
from html.parser import HTMLParser
from urllib.parse import urlencode
from urllib.request import Request, urlopen


LOG = logging.getLogger("email-whatsapp")


class _HTMLTextParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.parts: list[str] = []

    def handle_data(self, data: str) -> None:
        self.parts.append(data)


def _strip_html(value: str) -> str:
    parser = _HTMLTextParser()
    parser.feed(value)
    return " ".join("".join(parser.parts).split())


def _decode_header(value: str | None) -> str:
    if not value:
        return ""
    result: list[str] = []
    for part, encoding in decode_header(value):
        if isinstance(part, bytes):
            result.append(part.decode(encoding or "utf-8", errors="replace"))
        else:
            result.append(part)
    return "".join(result)


def _message_body(message: Message) -> str:
    plain_parts: list[str] = []
    html_parts: list[str] = []
    for part in message.walk() if message.is_multipart() else [message]:
        if part.get_content_maintype() == "multipart":
            continue
        payload = part.get_payload(decode=True)
        if payload is None:
            continue
        text = payload.decode(part.get_content_charset() or "utf-8", errors="replace")
        if part.get_content_type() == "text/plain":
            plain_parts.append(text)
        elif part.get_content_type() == "text/html":
            html_parts.append(_strip_html(text))
    return "\n".join(plain_parts or html_parts).strip()


def _format_whatsapp_message(message: Message, max_chars: int = 4000) -> str:
    sender_name, sender_address = parseaddr(_decode_header(message.get("From")))
    sender = f"{sender_name} <{sender_address}>" if sender_name else sender_address
    subject = _decode_header(message.get("Subject")) or "(no subject)"
    body = _message_body(message) or "(no text body)"
    formatted = f"New email\nFrom: {sender}\nSubject: {subject}\n\n{body}"
    return formatted[:max_chars - 1] + "…" if len(formatted) > max_chars else formatted


class Config:
    def __init__(self) -> None:
        required = {
            "IMAP_HOST": os.getenv("IMAP_HOST"),
            "IMAP_USERNAME": os.getenv("IMAP_USERNAME"),
            "IMAP_PASSWORD": os.getenv("IMAP_PASSWORD"),
            "TWILIO_ACCOUNT_SID": os.getenv("TWILIO_ACCOUNT_SID"),
            "TWILIO_AUTH_TOKEN": os.getenv("TWILIO_AUTH_TOKEN"),
            "TWILIO_WHATSAPP_FROM": os.getenv("TWILIO_WHATSAPP_FROM"),
            "WHATSAPP_TO": os.getenv("WHATSAPP_TO"),
        }
        missing = [name for name, value in required.items() if not value]
        if missing:
            raise ValueError(f"Missing environment variables: {', '.join(missing)}")
        self.imap_host = required["IMAP_HOST"]
        self.imap_port = int(os.getenv("IMAP_PORT", "993"))
        self.imap_username = required["IMAP_USERNAME"]
        self.imap_password = required["IMAP_PASSWORD"]
        self.imap_mailbox = os.getenv("IMAP_MAILBOX", "INBOX")
        self.twilio_account_sid = required["TWILIO_ACCOUNT_SID"]
        self.twilio_auth_token = required["TWILIO_AUTH_TOKEN"]
        self.whatsapp_from = required["TWILIO_WHATSAPP_FROM"]
        self.whatsapp_to = required["WHATSAPP_TO"]
        self.poll_seconds = int(os.getenv("POLL_SECONDS", "60"))


def send_whatsapp(config: Config, body: str) -> None:
    endpoint = (
        f"https://api.twilio.com/2010-04-01/Accounts/"
        f"{config.twilio_account_sid}/Messages.json"
    )
    credentials = f"{config.twilio_account_sid}:{config.twilio_auth_token}".encode()
    request = Request(
        endpoint,
        data=urlencode(
            {"From": config.whatsapp_from, "To": config.whatsapp_to, "Body": body}
        ).encode(),
        headers={
            "Authorization": "Basic " + base64.b64encode(credentials).decode(),
            "Content-Type": "application/x-www-form-urlencoded",
        },
        method="POST",
    )
    with urlopen(request, timeout=30) as response:
        if response.status >= 300:
            raise RuntimeError(f"Twilio returned HTTP {response.status}")


def process_unread(config: Config) -> int:
    forwarded = 0
    with imaplib.IMAP4_SSL(config.imap_host, config.imap_port) as mailbox:
        mailbox.login(config.imap_username, config.imap_password)
        status, _ = mailbox.select(config.imap_mailbox)
        if status != "OK":
            raise RuntimeError(f"Could not select mailbox {config.imap_mailbox!r}")
        status, data = mailbox.search(None, "UNSEEN")
        if status != "OK":
            raise RuntimeError("Could not search for unread email")
        for message_id in data[0].split():
            status, message_data = mailbox.fetch(message_id, "(RFC822)")
            if status != "OK" or not message_data or not isinstance(message_data[0], tuple):
                raise RuntimeError(f"Could not fetch email {message_id.decode()}")
            message = email.message_from_bytes(message_data[0][1])
            send_whatsapp(config, _format_whatsapp_message(message))
            mailbox.store(message_id, "+FLAGS", "\\Seen")
            forwarded += 1
            LOG.info("Forwarded email %s", message_id.decode())
    return forwarded


def main() -> None:
    logging.basicConfig(
        level=os.getenv("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s %(message)s",
    )
    config = Config()
    LOG.info("Watching %s every %s seconds", config.imap_mailbox, config.poll_seconds)
    while True:
        try:
            count = process_unread(config)
            if count:
                LOG.info("Forwarded %s email(s)", count)
        except Exception:
            LOG.exception("Forwarding cycle failed; will retry")
        time.sleep(config.poll_seconds)


if __name__ == "__main__":
    main()

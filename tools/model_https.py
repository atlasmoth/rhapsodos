import ipaddress
import re
import urllib.parse
import urllib.request


def valid_url_text(value):
    return (isinstance(value, str) and value and not any(
        character.isspace() or ord(character) < 32 or 127 <= ord(character) <= 159
        or character in "\\#" for character in value)
        and re.search(r"%(?![0-9a-fA-F]{2})", value) is None)


def https_url(value):
    if not valid_url_text(value) or not value.lower().startswith("https://"):
        raise ValueError("Use an HTTPS URL without credentials or fragments")
    authority = re.split(r"[/?]", value[8:], maxsplit=1)[0]
    if not authority or "@" in authority:
        raise ValueError("Invalid HTTPS authority")
    if authority.startswith("["):
        closing = authority.find("]")
        if closing < 0 or "%" in authority[:closing]:
            raise ValueError("Invalid IPv6 host")
        ipaddress.IPv6Address(authority[1:closing])
        remainder = authority[closing + 1:]
    else:
        host, separator, port = authority.partition(":")
        remainder = separator + port
        hostname = host.removesuffix(".")
        labels = hostname.split(".")
        if (not hostname or len(hostname) > 253 or any(
                re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label) is None
                for label in labels)):
            raise ValueError("Invalid HTTPS host")
        if len(labels) == 4 and all(label.isdigit() for label in labels):
            ipaddress.IPv4Address(hostname)
    if remainder and (not re.fullmatch(r":[0-9]+", remainder) or not 1 <= int(remainder[1:]) <= 65535):
        raise ValueError("Invalid HTTPS port")
    return urllib.parse.urlsplit(value)


def request_url(value):
    parts = https_url(value)
    return urllib.parse.urlunsplit(("https", parts.netloc,
        urllib.parse.quote(parts.path, safe="/:@!$&'()*+,;=-._~%"),
        urllib.parse.quote(parts.query, safe="/?:@!$&'()*+,;=-._~%"), ""))


class HTTPSRedirectHandler(urllib.request.HTTPRedirectHandler):
    def http_error_302(self, request, response, code, message, headers):
        location = headers.get("location", headers.get("uri"))
        if location is not None:
            try:
                if not valid_url_text(location):
                    raise ValueError("Invalid model redirect")
                https_url(urllib.parse.urljoin(request.full_url, location))
            except ValueError:
                response.close()
                raise
        return super().http_error_302(request, response, code, message, headers)

    http_error_301 = http_error_303 = http_error_307 = http_error_308 = http_error_302

    def redirect_request(self, request, response, code, message, headers, new_url):
        return super().redirect_request(request, response, code, message, headers, request_url(new_url))

package com.fherrmann.shopping.dto;

import java.util.List;

/**
 * „Abgehakte entfernen“ mit den Eintraegen, die der Client abgehakt gesehen hat. Ohne
 * Liste (Weboberflaeche, aeltere Apps) gehen alle abgehakten - auch einer, den die
 * andere Person gerade erst abgehakt hat, waehrend die App noch offline wartete.
 */
public record ClearCheckedRequest(List<String> ids) {
}

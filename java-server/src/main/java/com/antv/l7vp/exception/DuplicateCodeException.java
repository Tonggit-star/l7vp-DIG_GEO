package com.antv.l7vp.exception;

/**
 * 图标库号+代号重复异常
 */
public class DuplicateCodeException extends RuntimeException {

    private final String libraryCode;
    private final String codeName;

    public DuplicateCodeException(String libraryCode, String codeName) {
        super("同分类下已存在库号=" + libraryCode + " 代号=" + codeName + " 的图标");
        this.libraryCode = libraryCode;
        this.codeName = codeName;
    }

    public String getLibraryCode() {
        return libraryCode;
    }

    public String getCodeName() {
        return codeName;
    }
}

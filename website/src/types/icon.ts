export interface IconCategory {
  categoryId: string;
  categoryName: string;
  sortOrder: number;
  createTime: string;
}

export interface IconItem {
  iconId: string;
  categoryId: string;
  libraryCode: string;
  codeName: string;
  originalName: string;
  fileName: string;
  fileType: string;
  fileSize: number;
  url: string;
  sortOrder: number;
  createTime: string;
}

export interface IconCategoryLegacy {
  type: string;
  categoryId?: string;
  sortOrder?: number;
  icons: Array<{
    id: string;
    name: string;
    url: string;
    libraryCode?: string;
    codeName?: string;
  }>;
}

export type IconList = IconCategoryLegacy[];

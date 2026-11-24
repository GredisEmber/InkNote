package com.inknote.pad.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 笔记元数据数据库。
 *
 * exportSchema = true + 把 schema JSON 导出到 app/schemas：
 * 手写笔记是会长期积累的数据，schema 一定会变（v2 就加了纸型，
 * 以后还要加页、加标签）。没有历史 schema 就没法写可靠的 Migration，
 * 只能 destroyAndRecreate —— 那等于把用户的笔记删掉。
 */
@Database(
    entities = [NoteEntity::class, PageEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class InkDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao

    abstract fun pageDao(): PageDao

    companion object {
        private const val DB_NAME = "inknote.db"

        /**
         * v1 → v2：给笔记加上纸型。
         *
         * 只做加列，不动已有数据 —— 老笔记的 paper* 全是 NULL，
         * 运行时被识别为"纸型未知"，由编辑器从 .ink 文件头里读出尺寸回填。
         * 这样即便迁移逻辑本身有问题，后果也只是"纸型待确定"，
         * 而不是"笔记没了"。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN paperSizeName TEXT")
                db.execSQL("ALTER TABLE notes ADD COLUMN paperLandscape INTEGER")
                db.execSQL("ALTER TABLE notes ADD COLUMN paperWidthPx REAL")
                db.execSQL("ALTER TABLE notes ADD COLUMN paperHeightPx REAL")
            }
        }

        /**
         * v2 → v3：加 pages 表（多页支持）。
         *
         * 建表语句必须和 Room 生成的结构**逐字符对齐**，否则运行时会抛
         * "Migration didn't properly handle"。这里的 SQL 是照抄
         * app/schemas/…/3.json 里 Room 自己写的那份，
         * 改这里之前先去核对那份 schema。
         *
         * 索引名 `index_pages_noteId` 是 Room 对 Index("noteId") 的默认命名。
         *
         * 注意这个迁移**不碰文件系统** —— 老笔记的 page-000.ink 由仓库在
         * 首次打开时改名认领（见 RoomInkRepository.ensureLegacyPageAdopted）。
         * 迁移里做不了这件事：Room 的 migrate 只能执行 SQL。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pages` (" +
                        "`pageId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`noteId` INTEGER NOT NULL, " +
                        "`orderIndex` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pages_noteId` ON `pages` (`noteId`)",
                )
            }
        }

        /**
         * v3 → v4：给页面加书签列。
         *
         * 同样是"可空 + 无默认值"而不是 NOT NULL DEFAULT ——
         * 理由见 NoteEntity 的注释：Room 会逐列校验迁移后的结构和实体声明，
         * DEFAULT 的写法很容易写歪，而写歪的后果是运行期直接打不开 App。
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN bookmarkLabel TEXT")
            }
        }

        @Volatile
        private var instance: InkDatabase? = null

        fun get(context: Context): InkDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    InkDatabase::class.java,
                    DB_NAME,
                )
                    // 刻意不用 fallbackToDestructiveMigration：
                    // 那会在版本升级时默默删库，用户的笔记全部消失。
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}

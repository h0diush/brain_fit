# Room, Hilt и Compose поставляют собственные consumer-правила.
# Сохраняем имена enum-констант: они пишутся в базу как текст.
-keepclassmembers enum app.brainfit.domain.model.** { *; }

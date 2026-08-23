CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    -- Konuşma listesini sıralamak için; son mesaj gelene kadar boş.
    last_message_at TIMESTAMPTZ,
    -- Bire bir konuşmalarda iki üyenin sıralı kimlik çifti ("küçük:büyük").
    -- UNIQUE olduğu için aynı iki kişi arasında ikinci bir konuşma açılamıyor;
    -- "varsa getir, yoksa oluştur" bu sayede yarış koşulundan etkilenmiyor.
    direct_key TEXT UNIQUE
);

CREATE INDEX conversations_recent_idx ON conversations(last_message_at DESC NULLS LAST, id DESC);

CREATE TABLE conversation_members (
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL,
    -- Okundu su seviyesi. Okunmamış sayısı da "görüldü" bilgisi de bundan
    -- hesaplanıyor; mesaj başına ayrı satır tutmaya gerek kalmıyor.
    last_read_at TIMESTAMPTZ,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX conversation_members_user_idx ON conversation_members(user_id, conversation_id);

CREATE TABLE messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- TEXT | IMAGE | VIDEO | SYSTEM
    type VARCHAR(20) NOT NULL,
    body VARCHAR(4000),
    media_id UUID REFERENCES media_assets(id) ON DELETE SET NULL,
    -- SENT | DELETED
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Sohbet geçmişi yeniden eskiye okunuyor; imleç de bu yönde ilerliyor.
CREATE INDEX messages_conversation_idx ON messages(conversation_id, created_at DESC, id DESC);
